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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link LiteServerPool} 的离线调度测试——用 FakeClient 替身钉住轮换/冷却/首选记忆的全部语义，
 * 不触网、不依赖 ton4j。
 *
 * <h2>背景（2026-10 服务器实况）</h2>
 * <p>ton4j {@code AdnlLiteClient} 的 {@code useServerRotation} 只在「连接失败」时换节点；
 * 「区块不同步」的 query 失败（"block is not in db / out of sync"）直接抛——单节点抖动即整栈瘫痪。
 * 本池在应用层把「任何失败」都视为该节点当前不可用，换下一台重试。
 */
class LiteServerPoolTest {

    /** 可控时钟：从 fixed 起点开始，测试可推进。 */
    private static final class MutableClock extends Clock {
        private Instant instant;
        private final ZoneOffset zone;

        MutableClock(Instant start) {
            this.instant = start;
            this.zone = ZoneOffset.UTC;
        }

        void advance(Duration d) {
            instant = instant.plus(d);
        }

        @Override
        public ZoneOffset getZone() {
            return zone;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    /** 假节点客户端：记录调用/关闭；可配置「前 N 次调用失败」。 */
    private static final class FakeClient implements AutoCloseable {
        final int index;
        int remainingFailures;
        int calls;
        boolean closed;

        FakeClient(int index) {
            this.index = index;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    /** 假工厂：create 可配置失败；同一 index 复用同一实例（模拟真实连接复用）。 */
    private static final class FakeFactory implements LiteServerPool.Factory<FakeClient> {
        final Map<Integer, FakeClient> clients = new HashMap<>();
        final Map<Integer, Boolean> createFails = new HashMap<>();
        int createCalls;

        void failCreate(int index) {
            createFails.put(index, true);
        }

        void failOps(int index, int times) {
            client(index).remainingFailures = times;
        }

        FakeClient client(int index) {
            return clients.computeIfAbsent(index, FakeClient::new);
        }

        @Override
        public FakeClient create(int index) throws Exception {
            createCalls++;
            if (createFails.getOrDefault(index, false)) {
                throw new Exception("connect refused on node " + index);
            }
            return client(index);
        }
    }

    /** 每次 execute 让 client 按其配置成功/失败，返回成功节点的 index。 */
    private static int runOp(FakeClient c) throws Exception {
        c.calls++;
        if (c.remainingFailures > 0) {
            c.remainingFailures--;
            throw new Exception("query failed on node " + c.index);
        }
        return c.index;
    }

    private static void closeQuietly(FakeClient c) {
        c.close();
    }

    @Test
    @DisplayName("首节点成功 → 结果返回、首选不变")
    void firstNodeSucceeds() throws Exception {
        FakeFactory factory = new FakeFactory();
        try (LiteServerPool<FakeClient> pool =
                     new LiteServerPool<>(3, Clock.systemUTC(), factory, LiteServerPoolTest::closeQuietly)) {
            assertThat(pool.execute(LiteServerPoolTest::runOp)).isEqualTo(0);
            assertThat(factory.client(0).calls).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("节点 0 失败 → 自动换节点 1 成功，且节点 1 成为新首选")
    void failureRotatesToNextAndSticks() throws Exception {
        FakeFactory factory = new FakeFactory();
        factory.failOps(0, 1);
        try (LiteServerPool<FakeClient> pool =
                     new LiteServerPool<>(3, Clock.systemUTC(), factory, LiteServerPoolTest::closeQuietly)) {
            assertThat(pool.execute(LiteServerPoolTest::runOp)).isEqualTo(1);
            // 第二次 execute 直接从首选（1）开始——节点 0 不应被再次触碰
            assertThat(pool.execute(LiteServerPoolTest::runOp)).isEqualTo(1);
            assertThat(factory.client(0).calls).isEqualTo(1);
            assertThat(factory.client(1).calls).isEqualTo(2);
        }
    }

    @Test
    @DisplayName("create（建连）失败 → 冷却并换下一节点")
    void createFailureIsSkipped() throws Exception {
        FakeFactory factory = new FakeFactory();
        factory.failCreate(0);
        try (LiteServerPool<FakeClient> pool =
                     new LiteServerPool<>(3, Clock.systemUTC(), factory, LiteServerPoolTest::closeQuietly)) {
            assertThat(pool.execute(LiteServerPoolTest::runOp)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("全部节点失败 → ChainUnavailableException，消息含失败规模与最后错误")
    void allFailuresThrowChainUnavailable() {
        FakeFactory factory = new FakeFactory();
        factory.failOps(0, 1);
        factory.failOps(1, 1);
        factory.failOps(2, 1);
        assertThatThrownBy(() -> {
            try (LiteServerPool<FakeClient> pool =
                         new LiteServerPool<>(3, Clock.systemUTC(), factory, LiteServerPoolTest::closeQuietly)) {
                pool.execute(LiteServerPoolTest::runOp);
            }
        }).isInstanceOf(ChainUnavailableException.class)
                .hasMessageContaining("3")
                .hasMessageContaining("query failed on node 2");
    }

    @Test
    @DisplayName("失败节点进入冷却：30 秒内被跳过，冷却结束恢复尝试（可重建连接）")
    void failedNodeIsCooledDownThenRetried() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-03T12:00:00Z"));
        FakeFactory factory = new FakeFactory();
        try (LiteServerPool<FakeClient> pool =
                     new LiteServerPool<>(2, clock, factory, LiteServerPoolTest::closeQuietly)) {
            // 0 成功 → 首选 0
            assertThat(pool.execute(LiteServerPoolTest::runOp)).isEqualTo(0);

            // 0 失败 → 轮换到 1 成功；0 进冷却
            factory.failOps(0, 1);
            assertThat(pool.execute(LiteServerPoolTest::runOp)).isEqualTo(1);

            // 冷却期内：让 1 也失败，轮换到 0 时 0 处于冷却被跳过 → 全部不可用。
            // 判别点：若无冷却机制，0 此刻已恢复（remainingFailures 耗尽）会成功返回 0，
            // 而不是抛异常——本断言正是钉住「冷却期间不得触碰」。
            factory.failOps(1, 1);
            assertThatThrownBy(() -> pool.execute(LiteServerPoolTest::runOp))
                    .isInstanceOf(ChainUnavailableException.class)
                    .hasMessageContaining("冷却中");

            // 冷却结束：1 仍失败 → 轮换到 0（冷却已过，重建连接）→ 成功
            clock.advance(LiteServerPool.FAILED_COOLDOWN.plusSeconds(1));
            factory.failOps(1, 1);
            assertThat(pool.execute(LiteServerPoolTest::runOp)).isEqualTo(0);
        }
    }

    @Test
    @DisplayName("全部节点冷却中 → 抛异常（fail-closed），不无限等待")
    void allCooledDownFailsFast() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-03T12:00:00Z"));
        FakeFactory factory = new FakeFactory();
        factory.failOps(0, 1);
        factory.failOps(1, 1);
        assertThatThrownBy(() -> {
            try (LiteServerPool<FakeClient> pool = new LiteServerPool<>(2, clock, factory, LiteServerPoolTest::closeQuietly)) {
                pool.execute(LiteServerPoolTest::runOp);
            }
        }).isInstanceOf(ChainUnavailableException.class);
    }

    @Test
    @DisplayName("失败节点的 client 被关闭（不泄漏连接）")
    void failedClientIsClosed() throws Exception {
        FakeFactory factory = new FakeFactory();
        factory.failOps(0, 1);
        try (LiteServerPool<FakeClient> pool =
                     new LiteServerPool<>(2, Clock.systemUTC(), factory, LiteServerPoolTest::closeQuietly)) {
            pool.execute(LiteServerPoolTest::runOp);
            assertThat(factory.client(0).closed).isTrue();
            assertThat(factory.client(1).closed).isFalse();
        }
    }

    @Test
    @DisplayName("close 关闭全部存活 client")
    void closeClosesAllLiveClients() throws Exception {
        FakeFactory factory = new FakeFactory();
        factory.failOps(0, 1);
        LiteServerPool<FakeClient> pool =
                new LiteServerPool<>(3, Clock.systemUTC(), factory, LiteServerPoolTest::closeQuietly);
        // 0 失败 → 1 成功：此刻存活连接 = 1（0 已被淘汰关闭）；2 从未建连
        pool.execute(LiteServerPoolTest::runOp);
        assertThat(factory.clients).containsKeys(0, 1).doesNotContainKey(2);
        assertThat(factory.clients.get(0).closed).isTrue();

        pool.close();
        assertThat(factory.clients.get(1).closed).isTrue();
    }

    @Test
    @DisplayName("构造期预热：连到首个可达节点即停；全不可达 → 构造即抛（fail-fast）")
    void constructorPreconnectsFirstReachable() throws Exception {
        // 节点 0 可达 → 只 create 0
        FakeFactory okFactory = new FakeFactory();
        try (LiteServerPool<FakeClient> pool =
                     new LiteServerPool<>(3, Clock.systemUTC(), okFactory, LiteServerPoolTest::closeQuietly)) {
            assertThat(okFactory.createCalls).isEqualTo(1);
            assertThat(pool.execute(LiteServerPoolTest::runOp)).isEqualTo(0);
        }

        // 节点 0 建连失败 → 预热落到 1；首个 execute 也从 1 开始
        FakeFactory badFirst = new FakeFactory();
        badFirst.failCreate(0);
        try (LiteServerPool<FakeClient> pool =
                     new LiteServerPool<>(3, Clock.systemUTC(), badFirst, LiteServerPoolTest::closeQuietly)) {
            assertThat(pool.execute(LiteServerPoolTest::runOp)).isEqualTo(1);
        }

        // 全不可达 → 构造抛异常（装配期 fail-fast，不把失败推迟到首次请求）
        FakeFactory allBad = new FakeFactory();
        allBad.failCreate(0);
        allBad.failCreate(1);
        assertThatThrownBy(() -> new LiteServerPool<>(2, Clock.systemUTC(), allBad, LiteServerPoolTest::closeQuietly))
                .isInstanceOf(ChainUnavailableException.class);
    }

    @Test
    @DisplayName("0 节点 → 构造即抛")
    void zeroServersRejected() {
        assertThatThrownBy(() -> new LiteServerPool<>(0, Clock.systemUTC(), new FakeFactory(), LiteServerPoolTest::closeQuietly))
                .isInstanceOf(ChainUnavailableException.class);
    }

    @Test
    @DisplayName("并发 execute：全部成功且总调用数守恒（无连接重复建/丢失）")
    void concurrentExecutesAreSafe() throws Exception {
        FakeFactory factory = new FakeFactory();
        try (LiteServerPool<FakeClient> pool =
                     new LiteServerPool<>(3, Clock.systemUTC(), factory, LiteServerPoolTest::closeQuietly)) {
            int threads = 8;
            int perThread = 50;
            ExecutorService executor = Executors.newFixedThreadPool(threads);
            try {
                List<Future<Integer>> futures = new ArrayList<>();
                for (int t = 0; t < threads; t++) {
                    futures.add(executor.submit(() -> {
                        for (int i = 0; i < perThread; i++) {
                            int r = pool.execute(LiteServerPoolTest::runOp);
                            if (r != 0) {
                                throw new AssertionError("unexpected node " + r);
                            }
                        }
                        return perThread;
                    }));
                }
                int total = 0;
                for (Future<Integer> f : futures) {
                    total += f.get(30, TimeUnit.SECONDS);
                }
                assertThat(total).isEqualTo(threads * perThread);
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    @DisplayName("execute 抛出 VirtualMachineError（OOM 类）原样冒泡，不伪装成节点故障")
    void virtualMachineErrorPropagates() {
        FakeFactory factory = new FakeFactory();
        assertThatThrownBy(() -> {
            try (LiteServerPool<FakeClient> pool =
                         new LiteServerPool<>(2, Clock.systemUTC(), factory, LiteServerPoolTest::closeQuietly)) {
                pool.execute(c -> {
                    throw new OutOfMemoryError("boom");
                });
            }
        }).isInstanceOf(OutOfMemoryError.class);
    }

    @Test
    @DisplayName("close 后再 execute → 抛 ChainUnavailableException")
    void executeAfterCloseFails() {
        FakeFactory factory = new FakeFactory();
        LiteServerPool<FakeClient> pool = new LiteServerPool<>(2, Clock.systemUTC(), factory, LiteServerPoolTest::closeQuietly);
        pool.close();
        assertThatThrownBy(() -> pool.execute(LiteServerPoolTest::runOp))
                .isInstanceOf(ChainUnavailableException.class)
                .hasMessageContaining("已关闭");
    }
}
