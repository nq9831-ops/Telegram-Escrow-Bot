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

import org.ton.ton4j.adnl.AdnlLiteClient;
import org.ton.ton4j.address.Address;
import org.ton.ton4j.cell.Cell;
import org.ton.ton4j.cell.CellSlice;
import org.ton.ton4j.tl.liteserver.responses.RunMethodResult;
import org.ton.ton4j.tlb.VmStack;
import org.ton.ton4j.tlb.VmStackValue;
import org.ton.ton4j.tlb.VmStackValueInt;
import org.ton.ton4j.tlb.VmStackValueTinyInt;

import java.math.BigInteger;
import java.time.Clock;
import java.util.List;

/**
 * 合约链上状态的 ADNL 实现（ET-19 读面）——{@code get fun escrowState()}。
 *
 * <p>骨架与 {@link AdnlUpgradeStatusQuery} 逐段同款（惰性建连 / testnet 容错池 / mainnet
 * 单 client / {@code close()} 释放 / ton4j 裸 {@link Error} 兜底）。差异只有两处：
 * <b>① 单值返回</b>——合约 {@code return storage.state}，不存在 upgradeStatus 那种双值
 * 栈序问题（B9 教训不适用于此，但解析仍做越界防御）；<b>② 越界即异常</b>——0-5 之外的值
 * 视为解析错或新状态未同步，宁可大声失败也不把未知状态当事实喂给对账。
 */
public final class AdnlEscrowStateQuery implements EscrowStateQuery, AutoCloseable {

    private static final String GET_ESCROW_STATE = "escrowState";

    private final boolean testnet;
    /** testnet：容错池（多 liteserver 轮换）。 */
    private LiteServerPool<AdnlLiteClient> pool;
    /** mainnet：单 client（未池化——无内置 mainnet 配置，保持现状）。 */
    private AdnlLiteClient client;

    public AdnlEscrowStateQuery(boolean testnet) {
        this.testnet = testnet;
    }

    /** 由链上配置构造——把 {@link ChainSettings#testnet()} 接到 provider 选择上。 */
    public static AdnlEscrowStateQuery from(ChainSettings settings) {
        return new AdnlEscrowStateQuery(settings.testnet());
    }

    @Override
    public int queryState(String contractAddress) throws ChainUnavailableException {
        try {
            Address contract = Address.of(contractAddress);
            RunMethodResult result = runMethod(contract);
            if (result.getExitCode() != 0) {
                throw new ChainUnavailableException(
                        "escrowState 退出码非 0：" + result.getExitCode());
            }
            VmStack stack = VmStack.deserialize(CellSlice.beginParse(Cell.fromBoc(result.result)));
            return stateFromValues(stack.getStack().getTos());
        } catch (ChainUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new ChainUnavailableException("读合约链上状态失败：" + e.getMessage(), e);
        } catch (Error e) {
            // ton4j 裸 java.lang.Error 兜底（AdnlJettonWalletQuery 同款坑）
            throw new ChainUnavailableException("读合约链上状态失败：" + e.getMessage(), e);
        }
    }

    /** 从 get 方法返回栈解析状态（package-private：测试直调，不触网）。 */
    static int stateFromValues(List<VmStackValue> tos) throws ChainUnavailableException {
        if (tos == null || tos.isEmpty()) {
            throw new ChainUnavailableException("escrowState 返回空栈");
        }
        // 单值返回（合约 `return storage.state`）——无 upgradeStatus 那种双值栈序问题。
        BigInteger state = intValue(tos.get(0), "state");
        if (state.signum() < 0 || state.compareTo(BigInteger.valueOf(5)) > 0) {
            throw new ChainUnavailableException("escrowState 越界（0-5 之外）：" + state);
        }
        return state.intValueExact();
    }

    private static BigInteger intValue(VmStackValue value, String field)
            throws ChainUnavailableException {
        if (value == null) {
            throw new ChainUnavailableException("escrowState 的 " + field + " 为 null");
        }
        // TVmStack 的整数有两种序列化变体（tiny/大整数）——都要认，漏一个就把合法值误报为类型错
        if (value instanceof VmStackValueInt intVal) {
            return intVal.getValue();
        }
        if (value instanceof VmStackValueTinyInt tinyVal) {
            return tinyVal.getValue();
        }
        throw new ChainUnavailableException(
                "escrowState 的 " + field + " 不是整数：" + value.getClass().getSimpleName());
    }

    /** 懒建连：构造本对象不触网，首次查询才连 liteserver（testnet 走容错池，mainnet 单 client）。 */
    private RunMethodResult runMethod(Address contract) throws Exception {
        if (testnet) {
            return pool().execute(c -> c.runMethod(contract, GET_ESCROW_STATE));
        }
        return client().runMethod(contract, GET_ESCROW_STATE);
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
