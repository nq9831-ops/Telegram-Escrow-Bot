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

import com.iwebpp.crypto.TweetNaclFast;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.ton.ton4j.adnl.AdnlLiteClient;
import org.ton.ton4j.cell.Cell;
import org.ton.ton4j.cell.CellSlice;
import org.ton.ton4j.tlb.VmStack;
import org.ton.ton4j.tlb.VmStackValue;
import org.ton.ton4j.tlb.VmStackValueInt;
import org.ton.ton4j.tlb.VmStackValueTinyInt;

import java.math.BigInteger;
import java.time.Clock;

/**
 * B12 真机核销运行器：在演练合约上走完争议出款全链——
 * <b>Fund（1 TON 入金，state 0→1）→ Dispute（1→3）→ Resolve(release)（3→4）</b>。
 *
 * <h2>每步等链上状态推进（顺序是硬约束）</h2>
 * <p>合约对消息的状态守卫严格（Fund 要求 state==0、Dispute 要求 1|2、Resolve 要求 3）——
 * 不等前者落地就发后者，后者会在合约侧被拒（消息丢失且表面"发送成功"）。
 * 因此每步后轮询 {@code escrowState} get 方法（3s 间隔 × 15 次，超时即 fail）。
 *
 * <h2>演练简式</h2>
 * <p>三角色全 = 钱包自身：Fund 的钱从钱包出、Resolve RELEASE 的 1 TON 回钱包（卖方=钱包）；
 * 净成本 ≈ 三条消息的 gas。
 *
 * <p>启用（双闸同前）：{@code TGG_LIVE_DEPLOY=true} + 助记词环境变量。
 */
@EnabledIfEnvironmentVariable(named = "TGG_CHAIN_UPGRADE_WALLET_MNEMONIC", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TGG_LIVE_DEPLOY", matches = "true")
class S5LiveResolveProbeTest {

    private static final String CONTRACT_RAW =
            "0:276ec568859d0599c0cdad77dc0dd5cabfc333b2271146541d0dc66f98f02f4d";

    /** Fund 入金额（nanoton）：须 ≥ storage.amount（1 TON）——多出的部分留在合约。 */
    private static final long FUND_VALUE_NANOTON = 1_100_000_000L;

    /** 非资金消息的附着 value（gas 余量）。 */
    private static final long MSG_VALUE_NANOTON = 200_000_000L;

    @Test
    @DisplayName("B12：Fund → Dispute → Resolve(release)，每步轮询 escrowState 确认落地")
    void fullResolveChain() throws Exception {
        byte[] seed = ChainWalletKeys.seedFromMnemonic(
                System.getenv("TGG_CHAIN_UPGRADE_WALLET_MNEMONIC"));
        long walletId = Long.parseLong(System.getenv()
                .getOrDefault("TGG_CHAIN_UPGRADE_WALLET_ID", "2147483645"));
        var keyPair = TweetNaclFast.Signature.keyPair_fromSeed(seed);

        try (AdnlChainSender sender = AdnlChainSender.forNetwork(
                true, walletId, keyPair, Clock.systemUTC())) {
            sender.sendToContract(CONTRACT_RAW, FundMessageCodec.fund(), FUND_VALUE_NANOTON);
            System.out.println("FUND-SENT=OK（入金 " + FUND_VALUE_NANOTON + " nanoton）");
            waitForState(1);

            sender.sendToContract(CONTRACT_RAW, DisputeMessageCodec.dispute(), MSG_VALUE_NANOTON);
            System.out.println("DISPUTE-SENT=OK");
            waitForState(3);

            sender.sendToContract(CONTRACT_RAW, ResolveMessageCodec.resolve(true),
                    MSG_VALUE_NANOTON);
            System.out.println("RESOLVE-SENT=OK（release）");
            waitForState(4);
        }
        System.out.println("RESOLVE-CHAIN=COMPLETE（state 走完 0→1→3→4，1 TON 已出账给卖方=本钱包）");
    }

    /** 轮询 escrowState，等到期望值；超时（45s）即抛（真机核销不留假绿）。 */
    private static void waitForState(int expected) throws Exception {
        // AdnlLiteClient 有 close() 但未实现 AutoCloseable（ton4j 接口缺口）——用 try/finally
        AdnlLiteClient client = AdnlLiteClient.builder().testnet().build();
        try {
            for (int attempt = 1; attempt <= 15; attempt++) {
                long state = readEscrowState(client);
                if (state == expected) {
                    System.out.println("STATE=" + state + "（第 " + attempt + " 次轮询确认）");
                    return;
                }
                Thread.sleep(3_000);
            }
            throw new IllegalStateException("等待 escrowState=" + expected + " 超时（45s）");
        } finally {
            client.close();
        }
    }

    /** {@code escrowState()} get 方法读数（解析同 AdnlChainSender.seqnoFromResult 模式）。 */
    private static long readEscrowState(AdnlLiteClient client) throws Exception {
        var result = client.runMethod(org.ton.ton4j.address.Address.of(CONTRACT_RAW), "escrowState");
        if (result.getExitCode() != 0) {
            throw new IllegalStateException("escrowState 查询退出码非 0：" + result.getExitCode());
        }
        VmStack stack = VmStack.deserialize(CellSlice.beginParse(Cell.fromBoc(result.result)));
        VmStackValue top = stack.getStack().getTos().get(0);
        BigInteger value = (top instanceof VmStackValueInt intVal)
                ? intVal.getValue() : ((VmStackValueTinyInt) top).getValue();
        return value.longValueExact();
    }
}
