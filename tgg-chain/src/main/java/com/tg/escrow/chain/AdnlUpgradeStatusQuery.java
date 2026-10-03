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

import java.math.BigInteger;
import java.time.Clock;
import java.util.List;
import org.ton.ton4j.adnl.AdnlLiteClient;
import org.ton.ton4j.address.Address;
import org.ton.ton4j.cell.Cell;
import org.ton.ton4j.cell.CellSlice;
import org.ton.ton4j.tl.liteserver.responses.RunMethodResult;
import org.ton.ton4j.tlb.VmStack;
import org.ton.ton4j.tlb.VmStackValue;
import org.ton.ton4j.tlb.VmStackValueInt;
import org.ton.ton4j.tlb.VmStackValueTinyInt;

/**
 * 用 ton4j {@code AdnlLiteClient} 读合约 {@code get fun upgradeStatus}（⑥ 提案跟踪）。
 *
 * <p>手法与 {@link AdnlJettonWalletQuery} 同源：懒建连、裸 {@code Error} 兜底、
 * 自己反序列化 {@code VmStack}（不用 {@code getCellByIndex} 那类按 cell 强转的入口）。
 *
 * <h2>栈序口径（真机核对项）</h2>
 * <p><b>栈序口径（2026-10-02 真机核销定案，B9）</b>：合约返回 {@code (codeHash, proposedAt)}，
 * 实测 {@code tos[0]=codeHash（256 位）、tos[1]=proposedAt（unix 秒）}——返回顺序即栈序。
 * 核销证据：propose 后 {@code upgradeStatus} 读回 tos[0]=766CE1EC… 对应的 256 位大数、
 * tos[1]=1790940921（≈发送时刻）；cancel 后归 (0,0)（提交 S5LiveJettonUpgradeProbeTest）。
 *
 * <p><b>未经真实节点验证</b>（同 {@link AdnlJettonWalletQuery} 口径）。
 */
public final class AdnlUpgradeStatusQuery implements UpgradeStatusQuery, AutoCloseable {

    private static final String GET_UPGRADE_STATUS = "upgradeStatus";

    private final boolean testnet;
    /** testnet：多 lite-server 容错池（懒建；单节点抖动自动轮换——见 {@link LiteServerPool}）。 */
    private LiteServerPool<AdnlLiteClient> pool;
    /** mainnet：单 client（未池化——无内置 mainnet 配置，保持现状）。 */
    private AdnlLiteClient client;

    public AdnlUpgradeStatusQuery(boolean testnet) {
        this.testnet = testnet;
    }

    /** 由链上配置构造——把 {@link ChainSettings#testnet()} 接到 provider 选择上。 */
    public static AdnlUpgradeStatusQuery from(ChainSettings settings) {
        return new AdnlUpgradeStatusQuery(settings.testnet());
    }

    @Override
    public UpgradeStatus query(String contractAddress) throws ChainUnavailableException {
        try {
            Address contract = Address.of(contractAddress);
            RunMethodResult result = runMethod(contract);
            if (result.getExitCode() != 0) {
                throw new ChainUnavailableException(
                        "upgradeStatus 退出码非 0：" + result.getExitCode());
            }
            VmStack stack = VmStack.deserialize(CellSlice.beginParse(Cell.fromBoc(result.result)));
            return statusFromValues(stack.getStack().getTos());
        } catch (ChainUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new ChainUnavailableException("读升级提案状态失败：" + e.getMessage(), e);
        } catch (Error e) {
            // ton4j 裸 java.lang.Error 兜底（AdnlJettonWalletQuery 同款坑）
            throw new ChainUnavailableException("读升级提案状态失败：" + e.getMessage(), e);
        }
    }

    /**
     * 从栈值解析提案状态（纯逻辑，可离线测试）。
     *
     * @param tos ton4j 栈值列表（栈顶在前）
     */
    static UpgradeStatus statusFromValues(List<VmStackValue> tos) throws ChainUnavailableException {
        if (tos == null || tos.size() < 2) {
            throw new ChainUnavailableException("upgradeStatus 返回栈不足两值");
        }
        // 口径（2026-10-02 真机核销定案，B9）：tos[0]=codeHash（256 位）、tos[1]=proposedAt。
        // 合约 `return (newCode.hash(), proposedAt)` 的返回顺序即栈序（ton4j 的 tos 列表栈顶在前，
        // 实测 tos[0] 为 766CE1EC… 的 256 位大数、tos[1] 为 unix 秒）。原推定「后返回值在栈顶」
        // 被真机证伪——两值恰好互换（无提案 (0,0) 交叉校验对两种口径都成立，故只能靠真实提案定案）。
        BigInteger codeHash = intValue(tos.get(0), "codeHash");
        BigInteger proposedAt = intValue(tos.get(1), "proposedAt");
        return new UpgradeStatus(codeHash, proposedAt.longValueExact());
    }

    private static BigInteger intValue(VmStackValue value, String field)
            throws ChainUnavailableException {
        // TVmStack 的整数有两种序列化变体（tiny/大整数）——都要认，漏一个就把合法值误报为类型错
        if (value instanceof VmStackValueInt intVal) {
            return intVal.getValue();
        }
        if (value instanceof VmStackValueTinyInt tinyVal) {
            return tinyVal.getValue();
        }
        throw new ChainUnavailableException(
                "upgradeStatus 的 " + field + " 不是整数：" + value.getClass().getSimpleName());
    }

    /** 懒建连：构造本对象不触网，首次查询才连 liteserver（testnet 走容错池，mainnet 单 client）。 */
    private RunMethodResult runMethod(Address contract) throws Exception {
        if (testnet) {
            return pool().execute(c -> c.runMethod(contract, GET_UPGRADE_STATUS));
        }
        return client().runMethod(contract, GET_UPGRADE_STATUS);
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
