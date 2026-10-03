/*
 * SPDX-License-Identifier: AGPL-3.0-only
 * Copyright (C) 2026 telegram-escrow-bot contributors
 *
 * This file is part of telegram-escrow-bot.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, version 3 of the License only.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along
 * with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * NOTE: the SPDX identifier is AGPL-3.0-only because the LICENSE file in this
 * repository carries the plain AGPL v3 text without an "or later" grant. If you
 * intend to allow later versions, change this line to AGPL-3.0-or-later and
 * make the LICENSE wording match — the two must not disagree.
 */
package com.tg.escrow.chain;

import com.google.gson.GsonBuilder;
import com.google.gson.ToNumberPolicy;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import org.ton.ton4j.adnl.AdnlLiteClient;
import org.ton.ton4j.adnl.globalconfig.TonGlobalConfig;

/**
 * 多 lite-server 容错池——应用层轮换，替代 ton4j 的 {@code useServerRotation}。
 *
 * <h2>为什么要有这一层（2026-10 服务器实况定案）</h2>
 * <p>ton4j 2.1.0 {@code AdnlLiteClient} 的 {@code useServerRotation}/{@code maxRetries} 只在
 * <b>连接失败</b>（{@code IllegalStateException("Not connected to lite-server")}）时换节点；
 * 「节点可达但区块不同步」的 query 失败（"block is not in db / possibly out of sync"）落在
 * {@code executeWithRetry} 的 {@code catch (Exception)} 分支——直接 {@code throw}，不重试、不换节点。
 * 单节点抖动即整栈瘫痪（实测：seqno runMethod 被拒，联邦钱包出款整个卡死）。
 *
 * <p>本池把「任何失败」（连接失败、建连失败、query 失败、广播失败）都视为该节点当前不可用，
 * 换下一台重试；全部失败才抛 {@link ChainUnavailableException}（fail-closed，不静默降级）。
 *
 * <h2>语义要点</h2>
 * <ul>
 *   <li><b>首选记忆</b>：上次成功节点为下次起点——好节点一次选中反复使用，不每次从头碰运气；</li>
 *   <li><b>冷却</b>：失败节点 30 秒内不再尝试（{@link #FAILED_COOLDOWN}），避免每次请求都在坏节点
 *       上浪费一个超时；冷却结束自动恢复（节点可能已追上区块）；</li>
 *   <li><b>构造期预热（fail-fast）</b>：构造即建连直到首个可达节点；全部不可达 → 构造抛异常
 *       ——装配期失败，不把「环境坏了」推迟到首个业务请求；</li>
 *   <li><b>广播重发安全</b>：同一条已签名 external 消息换节点重发是幂等的（钱包按 seqno 去重），
 *       故广播失败同样走轮换重试；</li>
 *   <li><b>并发</b>：内部状态由锁保护，操作执行在锁外（网络 IO 不串行化）；失败节点的连接
 *       被关闭丢弃（不泄漏），下次使用重建。</li>
 * </ul>
 *
 * <h2>为什么不改 mainnet 路径</h2>
 * <p>mainnet 侧无内置全局配置（ton4j {@code builder().mainnet()} 每次从 GitHub 拉取），
 * 保持单 client 现状；池化只覆盖 testnet（内置配置 8 节点）。真要用 mainnet 池化，
 * 传入一份 mainnet {@link TonGlobalConfig} 给 {@link #forConfig} 即可。
 *
 * @param <R> 节点客户端类型（生产为 {@link AdnlLiteClient}；测试为离线替身）。
 *            注意：ton4j 的 {@code AdnlLiteClient} 有 {@code close()} 方法但<b>没有</b>
 *            {@code implements AutoCloseable}——故关闭动作经 {@link Closer} 显式注入。
 */
public final class LiteServerPool<R> implements AutoCloseable {

    /** 失败节点的冷却时长：冷却期内不再尝试（节点追区块通常以秒计，30s 足够恢复一次）。 */
    public static final Duration FAILED_COOLDOWN = Duration.ofSeconds(30);

    /** 池操作：在给定客户端上执行一次查询/广播。 */
    @FunctionalInterface
    public interface Op<T, R> {
        T call(R client) throws Exception;
    }

    /** 客户端工厂：按节点 index 建一个连接（ton4j 的 build 即连接）。 */
    @FunctionalInterface
    public interface Factory<R> {
        R create(int index) throws Exception;
    }

    /** 关闭动作：释放一个节点的连接（生产 = {@code AdnlLiteClient::close}）。 */
    @FunctionalInterface
    public interface Closer<R> {
        void close(R client) throws Exception;
    }

    private final int serverCount;
    private final Clock clock;
    private final Factory<R> factory;
    private final Closer<R> closer;
    private final long cooldownMillis;

    /** 状态锁：保护 {@link #clients} 与 {@link #failedUntil}。 */
    private final Object lock = new Object();
    private final R[] clients;
    private final long[] failedUntil; // epoch milli；0 = 可用
    /** 上次成功节点（下一次 execute 的起点）。volatile：操作在锁外读。 */
    private volatile int preferred;
    private volatile boolean closed;

    /**
     * 构造并预热：建连直到首个可达节点（fail-fast，全部不可达即抛）。
     *
     * @param serverCount 节点数（&gt; 0）
     * @param clock       时钟（一律注入，冷却判定可测）
     * @param factory     客户端工厂（按 index 建连）
     * @param closer      关闭动作（淘汰/关闭池时释放连接）
     */
    public LiteServerPool(int serverCount, Clock clock, Factory<R> factory, Closer<R> closer)
            throws ChainUnavailableException {
        if (serverCount <= 0) {
            throw new ChainUnavailableException("lite-server 节点数为 " + serverCount + "，不能建池");
        }
        this.serverCount = serverCount;
        this.clock = Objects.requireNonNull(clock, "时钟未提供");
        this.factory = Objects.requireNonNull(factory, "客户端工厂未提供");
        this.closer = Objects.requireNonNull(closer, "关闭动作未提供");
        this.cooldownMillis = FAILED_COOLDOWN.toMillis();
        @SuppressWarnings("unchecked")
        R[] arr = (R[]) new Object[serverCount];
        this.clients = arr;
        this.failedUntil = new long[serverCount];

        // 预热：找到首个可达节点（全失败 → 装配期 fail-fast）
        String lastError = null;
        for (int attempt = 0; attempt < serverCount; attempt++) {
            int idx = Math.floorMod(preferred + attempt, serverCount);
            R client = ensureClient(idx);
            if (client != null) {
                preferred = idx;
                return;
            }
            lastError = "节点 " + idx + " 建连失败";
        }
        throw new ChainUnavailableException(
                "全部 " + serverCount + " 个 lite-server 建连失败，写链栈不可用（最后错误："
                        + safe(lastError) + "）");
    }

    /**
     * 在池上执行一次操作：从首选节点开始轮换，任何失败换下一台；全部失败抛契约异常。
     *
     * <p>成功返回后，成功节点成为新首选。{@link VirtualMachineError}（OOM 类环境故障）原样冒泡，
     * 不伪装成节点故障。
     */
    public <T> T execute(Op<T, R> op) throws ChainUnavailableException {
        Objects.requireNonNull(op, "池操作未提供");
        if (closed) {
            throw new ChainUnavailableException("lite-server 池已关闭");
        }
        String lastError = null;
        int tried = 0;
        int cooledSkipped = 0;
        long now = clock.millis();
        for (int attempt = 0; attempt < serverCount; attempt++) {
            int idx = Math.floorMod(preferred + attempt, serverCount);

            R client;
            synchronized (lock) {
                if (closed) {
                    throw new ChainUnavailableException("lite-server 池已关闭");
                }
                if (failedUntil[idx] > now) {
                    cooledSkipped++;
                    continue;
                }
                client = ensureClient(idx);
                if (client == null) {
                    tried++;
                    lastError = "节点 " + idx + " 建连失败";
                    continue;
                }
            }

            try {
                T result = op.call(client);
                preferred = idx;
                return result;
            } catch (VirtualMachineError e) {
                throw e;
            } catch (Exception | Error e) {
                tried++;
                lastError = errorText(e, idx);
                retire(idx);
            }
        }

        String detail = "尝试 " + tried + " 个节点失败" + (cooledSkipped > 0
                ? "、" + cooledSkipped + " 个冷却中" : "") + "，最后错误：" + safe(lastError);
        throw new ChainUnavailableException(
                "全部 " + serverCount + " 个 lite-server 均不可用（" + detail + "）");
    }

    /**
     * 取 index 的现有连接，不存在则建连（ton4j build 即连接）。建连失败记录冷却并返回 null。
     * 调用方必须持有 {@link #lock}。
     */
    private R ensureClient(int idx) {
        R client = clients[idx];
        if (client != null) {
            return client;
        }
        try {
            client = factory.create(idx);
            clients[idx] = client;
            return client;
        } catch (VirtualMachineError e) {
            throw e;
        } catch (Exception | Error e) {
            failedUntil[idx] = clock.millis() + cooldownMillis;
            return null;
        }
    }

    /** 淘汰失败节点：关闭连接、置空、进冷却。 */
    private void retire(int idx) {
        R toClose;
        synchronized (lock) {
            toClose = clients[idx];
            clients[idx] = null;
            failedUntil[idx] = clock.millis() + cooldownMillis;
        }
        if (toClose != null) {
            try {
                closer.close(toClose);
            } catch (Exception | Error ignored) {
                // 关闭失败不影响池语义——连接已被丢弃
            }
        }
    }

    private static String errorText(Throwable e, int idx) {
        return "节点 " + idx + "：" + (e.getMessage() != null
                ? e.getMessage() : e.getClass().getSimpleName());
    }

    private static String safe(String s) {
        return s == null ? "未知" : s;
    }

    /** 全部存活连接关闭（幂等）。 */
    @Override
    public void close() {
        closed = true;
        R[] toClose;
        synchronized (lock) {
            toClose = clients.clone();
            java.util.Arrays.fill(clients, null);
        }
        for (R c : toClose) {
            if (c != null) {
                try {
                    closer.close(c);
                } catch (Exception | Error ignored) {
                    // 关闭失败不阻断其他节点关闭
                }
            }
        }
    }

    /**
     * 读内置 testnet 全局配置（classpath 资源 {@code /ton-testnet.global.config.json}）。
     *
     * <p><b>为什么内置（2026-10-02 真机教训）</b>：ton4j 的 {@code builder().testnet()} 每次都要
     * 从 GitHub 拉全局配置——GitHub 不可达时（实测）整个写链栈起不来（Socket 超时 2 分钟）。
     * 内置资源内容为公开的 testnet 节点目录（源自 ton.org 官方 testnet-global.config.json）。
     *
     * @return 解析成功的配置；资源缺失/损坏返回 {@code null}（调用方自行决定回退策略）
     */
    public static TonGlobalConfig loadBundledTestnetConfig() {
        try (var in = LiteServerPool.class
                .getResourceAsStream("/ton-testnet.global.config.json")) {
            if (in == null) {
                return null;
            }
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return new GsonBuilder()
                    .setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
                    .create()
                    .fromJson(json, TonGlobalConfig.class);
        } catch (Exception | Error e) {
            return null;
        }
    }

    /**
     * 由全局配置建池：每个 liteserver 一个固定 index 的 {@link AdnlLiteClient}
     * （{@code liteServerIndex(i)} 关掉 ton4j 内部 rotation，轮换全权归本池）。
     *
     * <p>{@code maxRetries(1)}：建连失败立即交给本池换下一台，不让 ton4j 在同一台重连 5 次
     * 放大坏节点的时间成本。
     */
    public static LiteServerPool<AdnlLiteClient> forConfig(TonGlobalConfig config, Clock clock)
            throws ChainUnavailableException {
        Objects.requireNonNull(config, "全局配置未提供");
        if (config.getLiteservers() == null || config.getLiteservers().length == 0) {
            throw new ChainUnavailableException("全局配置不含任何 liteserver");
        }
        int count = config.getLiteservers().length;
        return new LiteServerPool<>(count, clock, idx -> AdnlLiteClient.builder()
                .globalConfig(config)
                .liteServerIndex(idx)
                .maxRetries(1)
                .build(), AdnlLiteClient::close);
    }

    /**
     * 由内置 testnet 配置建池（生产 testnet 装配入口）。
     *
     * @throws ChainUnavailableException 内置配置缺失/无效，或全部节点建连失败
     */
    public static LiteServerPool<AdnlLiteClient> forBundledTestnet(Clock clock)
            throws ChainUnavailableException {
        TonGlobalConfig config = loadBundledTestnetConfig();
        if (config == null) {
            throw new ChainUnavailableException("内置 testnet 全局配置缺失或损坏");
        }
        return forConfig(config, clock);
    }
}
