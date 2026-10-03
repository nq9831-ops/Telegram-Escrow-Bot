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

import java.time.Clock;
import org.ton.ton4j.adnl.AdnlLiteClient;
import org.ton.ton4j.address.Address;
import org.ton.ton4j.cell.Cell;
import org.ton.ton4j.cell.CellBuilder;
import org.ton.ton4j.cell.CellSlice;
import org.ton.ton4j.smartcontract.token.nft.NftUtils;
import org.ton.ton4j.tl.liteserver.responses.RunMethodResult;
import org.ton.ton4j.tlb.VmCellSlice;
import org.ton.ton4j.tlb.VmStack;
import org.ton.ton4j.tlb.VmStackValue;
import org.ton.ton4j.tlb.VmStackValueSlice;

/**
 * 用 ton4j 的 <b>AdnlLiteClient</b>（纯 Java ADNL lite-client）查询 jetton 钱包地址。
 *
 * <h2>为什么换成 adnl 而不是继续用 Tonlib</h2>
 * <p>ton4j 的 {@code Tonlib} 要经 JNA 加载原生 {@code libtonlibjson}：线上是受限容器，
 * 实测直接 {@code IllegalArgumentException: Invalid library name ""}（原生库不存在）。
 * {@code AdnlLiteClient} 是全 Java 实现，免原生化，少一类 glibc/动态库耦合。
 *
 * <p>历史上并存过一条 {@code TonlibJettonWalletQuery} 备选路线。它需要原生库、且从未被生产装配，
 * 已删除——留着一个不被装配的 provider，只会让"到底在用哪条路线"变成需要猜的事。
 * 真要在有原生库的环境回退，从 git 历史取回即可。
 *
 * <h2>它为什么必须实现同一个接缝</h2>
 * <p>{@link JettonWalletQuery} 就是「可被离线替身替换」的那个点：上层
 * （{@link JettonWalletAddressResolver}）只认这个接口，因此测试可以用一个 lambda 替身
 * 把整条判定链路离线钉住，不依赖任何节点。
 *
 * <h2>调用与解析方式来自库源码，不是猜的</h2>
 * <p>签名与用法逐条对照 {@code adnl-2.1.0-sources.jar} 与 {@code tlb-2.1.0-sources.jar} 核实：
 * <ul>
 *   <li>带参数的入口是 {@code runMethod(Address, String, VmStackValue...)}，它把参数打包成
 *       {@code VmStack} 后调底层 {@code runMethod(BlockIdExt, mode=4, ...)}，并在内部
 *       {@code catch (Exception) → throw new Error(...)} ——即<b>失败可能是裸 Error</b>；</li>
 *   <li>{@code VmStackValueSlice.toCell()} 自己写死 magic {@code 0x04}，无需调用方设置；</li>
 *   <li>{@code VmCellSlice.toCell()} 在 {@code stBits/endBits/stRef/endRef} 为 0 时自动取
 *       「整个 cell」，所以构造「整地址 slice」不必手填位宽；</li>
 *   <li>{@code RunMethodResult.getCellByIndex(int)} 会强转 {@code VmStackValueCell}，
 *       而 {@code get_wallet_address} 返回的是 <b>slice</b> —— 走它会 ClassCastException，
 *       故本类自己反序列化 {@code VmStack} 取 slice。</li>
 * </ul>
 *
 * <h2>验证状态（如实标注）</h2>
 * <p><b>未经真实节点验证</b>：本类需要能连上 TON liteserver（测试网/主网），本地环境无法完成
 * 该验证。已离线验证的是两个纯逻辑步骤——{@link #ownerArg(Address)} 的参数构造与
 * {@link #addressFromResult(byte[])} 的结果解析（往返测试）。
 * 在真机链路跑通之前，<b>不得</b>把本类描述为「可用」。
 */
public final class AdnlJettonWalletQuery implements JettonWalletQuery, AutoCloseable {

    /** 标准 get-method：问 master 某个 owner 对应的钱包地址（对任何 jetton 变体都成立）。 */
    private static final String GET_WALLET_ADDRESS = "get_wallet_address";

    private final boolean testnet;
    /** testnet：多 lite-server 容错池（懒建；任何节点失败自动换下一台——见 {@link LiteServerPool}）。 */
    private LiteServerPool<AdnlLiteClient> pool;
    /** mainnet：单 client（未池化——无内置 mainnet 配置，保持现状）。 */
    private AdnlLiteClient client;

    public AdnlJettonWalletQuery(boolean testnet) {
        this.testnet = testnet;
    }

    /** 由链上配置构造——把 {@link ChainSettings#testnet()} 接到 provider 选择上。 */
    public static AdnlJettonWalletQuery from(ChainSettings settings) {
        return new AdnlJettonWalletQuery(settings.testnet());
    }

    @Override
    public String queryWalletAddress(String jettonMaster, String ownerAddress)
            throws ChainUnavailableException {
        try {
            Address master = Address.of(jettonMaster);
            Address owner = Address.of(ownerAddress);

            RunMethodResult result = runMethod(master, ownerArg(owner));
            if (result.getExitCode() != 0) {
                throw new ChainUnavailableException(
                        "get_wallet_address 退出码非 0：" + result.getExitCode());
            }
            return addressFromResult(result.result, testnet);
        } catch (ChainUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new ChainUnavailableException("查询 jetton 钱包地址失败：" + e.getMessage(), e);
        } catch (VirtualMachineError e) {
            // OOM / StackOverflow 属环境级故障，必须原样冒泡——包装成"链不可用"会掩盖真因
            throw e;
        } catch (Error e) {
            // ton4j 用**裸 java.lang.Error** 表达业务性失败（AdnlLiteClient 内部
            // catch(Exception) 后 throw new Error(...)）。catch(Exception) 拦不住它，
            // 会直接击穿本方法的契约（「拿不到结果时抛 ChainUnavailableException」）。
            throw new ChainUnavailableException("查询 jetton 钱包地址失败：" + e.getMessage(), e);
        }
    }

    /**
     * 构造 {@code get_wallet_address} 的栈参数：一个 slice，内容就是 owner 地址。
     *
     * <p><b>纯逻辑，可离线测试</b>。写法对照 tlb 源码：{@code VmCellSlice} 的位宽字段留 0
     * 即表示「整个 cell」，{@code VmStackValueSlice} 的 magic 由它自己的 {@code toCell()} 写入。
     */
    static VmStackValue ownerArg(Address owner) {
        return VmStackValueSlice.builder()
                .cell(VmCellSlice.builder()
                        .cell(CellBuilder.beginCell().storeAddress(owner).endCell())
                        .build())
                .build();
    }

    /**
     * 从 {@code runMethod} 的 {@code result}（boc 字节）解析出钱包地址。
     *
     * <p><b>纯逻辑，可离线测试</b>。刻意不用 {@code RunMethodResult.getCellByIndex(int)}——
     * 它强转 {@code VmStackValueCell}，而本 get-method 返回的是 slice，会 ClassCastException。
     *
     * @param resultBoc get-method 返回的 boc
     * @param testnet   网络标志——决定输出地址的 tag 形态（testnet=kQ / mainnet=EQ）。
     *                  <b>必须走独立方法</b>：ton4j 的 {@code toString(urlSafe, bounceable, …)}
     *                  系列不携带 testnet 语义（三参实测恒出 EQ，第三参是 bounceable 位），
     *                  testnet 形态只存在于 {@code toBounceableTestnet()}。
     */
    static String addressFromResult(byte[] resultBoc, boolean testnet) throws ChainUnavailableException {
        if (resultBoc == null || resultBoc.length == 0) {
            throw new ChainUnavailableException("get_wallet_address 返回空结果");
        }
        VmStack stack;
        try {
            stack = VmStack.deserialize(CellSlice.beginParse(Cell.fromBoc(resultBoc)));
        } catch (VirtualMachineError e) {
            // OOM / StackOverflow 属环境级故障，必须原样冒泡
            throw e;
        } catch (Exception | Error e) {
            // ton4j 的 Cell.fromBoc 对非法输入抛的是**裸 java.lang.Error("Invalid boc")**（实测，
            // 见 AdnlJettonWalletQueryTest.invalidResultIsReportedAsChainUnavailable）——
            // catch(Exception) 拦不住它，而本方法的契约是「解析失败必抛 ChainUnavailableException」。
            throw new ChainUnavailableException("get_wallet_address 结果不是合法 boc：" + e.getMessage(), e);
        }
        if (stack.getStack() == null || stack.getStack().getTos().isEmpty()) {
            throw new ChainUnavailableException("get_wallet_address 返回空栈");
        }
        VmStackValue top = stack.getStack().getTos().get(0);
        if (!(top instanceof VmStackValueSlice slice)) {
            throw new ChainUnavailableException(
                    "get_wallet_address 返回的不是 slice：" + top.getClass().getSimpleName());
        }
        Address wallet = NftUtils.parseAddress(slice.getCell().getCell());
        if (wallet == null) {
            throw new ChainUnavailableException("get_wallet_address 解析出空地址");
        }
        // tag 形态随网络切换（缺陷修复）：toString(true,true,true) 恒出 mainnet EQ——
        // testnet 形态在 ton4j 里是独立方法。同一 workchain+hash，仅 tag 与 CRC 不同。
        return testnet ? wallet.toBounceableTestnet() : wallet.toString(true, true, true);
    }

    /**
     * 懒建连接：构造本对象不触网，首次查询才连 liteserver。
     *
     * <p>testnet 走 {@link LiteServerPool}（内置配置 8 节点，单节点抖动自动轮换；ton4j 的
     * {@code useServerRotation} 对「区块不同步」不换节点，见池类注释）；mainnet 保持单 client。
     *
     * <p><b>为什么整个方法加锁</b>：先前是无同步的 check-then-act——{@code /admin/chain} 端点
     * 可能被并发请求同时命中，两个线程各自建出一个 {@code AdnlLiteClient}，其中一个随即被覆盖，
     * 而<b>它的连接不会被 close</b>（泄漏）。加锁后建连只发生一次；首次建连期间其它请求等待，
     * 而那正是期望行为（建连本身是重操作）。
     */
    private RunMethodResult runMethod(Address master, VmStackValue arg) throws Exception {
        if (testnet) {
            return pool().execute(c -> c.runMethod(master, GET_WALLET_ADDRESS, arg));
        }
        return client().runMethod(master, GET_WALLET_ADDRESS, arg);
    }

    private synchronized LiteServerPool<AdnlLiteClient> pool() throws ChainUnavailableException {
        if (pool == null) {
            pool = LiteServerPool.forBundledTestnet(Clock.systemUTC());
        }
        return pool;
    }

    private synchronized AdnlLiteClient client() throws Exception {
        if (client == null) {
            client = AdnlLiteClient.builder().mainnet().build();
        }
        return client;
    }

    @Override
    public synchronized void close() {
        if (pool != null) {
            pool.close();
            pool = null;
        }
        if (client != null) {
            client.close();
            client = null;
        }
    }
}
