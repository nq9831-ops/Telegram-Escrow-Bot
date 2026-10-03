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

import java.math.BigInteger;
import java.time.Clock;

/**
 * B10 真机核销运行器：向 testnet 发送一笔真实的 S5 部署消息（2026-10-02 首次真机写链）。
 *
 * <h2>它核销什么</h2>
 * <ul>
 *   <li><b>B8（签名载荷口径）</b>——首笔写链即核：联邦钱包（v5r1）接受我们的签名并转发内部消息；
 *       若口径错，链会拒绝 external，本运行器的"广播成功但链上无账户"组合即是证据；</li>
 *   <li><b>B10 第一环</b>——部署消息经 ADNL 广播后，合约地址在链上从 uninit → active
 *       （用 {@code acton rpc info <CONTRACT>} 确认）。</li>
 * </ul>
 *
 * <h2>演练简式</h2>
 * <p>买方/卖方/联邦三角色全用钱包自身地址；TON 单（asset=0）、W=零哨兵（TON 单不需要两阶段 W）；
 * amount=1 TON 仅登记（部署不转移资金）。产出的是一个"孤儿演练实例"——测试网、无业务归属，
 * 后续 B11/B12 可继续拿它演练。
 *
 * <h2>启用（双闸，防误跑）</h2>
 * <pre>{@code
 * set -a; source env.local; set +a
 * TGG_LIVE_DEPLOY=true mvn -B -pl tgg-chain -am test \
 *   -Dtest=S5LiveDeployProbeTest -Dsurefire.failIfNoSpecifiedTests=false
 * }</pre>
 * 观察顺序：① 本测试打印 {@code DEPLOY-BROADCAST=OK}（同步广播未抛）；
 * ② 等 ~15s 后 {@code acton rpc info <CONTRACT=kQ…>} 看账户 active；
 * ③ {@code acton wallet list --balance} 看钱包余额扣减。
 */
@EnabledIfEnvironmentVariable(named = "TGG_CHAIN_UPGRADE_WALLET_MNEMONIC", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TGG_LIVE_DEPLOY", matches = "true")
class S5LiveDeployProbeTest {

    /** 部署附着金额（nanoton）：0.3 GRAM——OPEN 空转合约只需少量初始余额。 */
    private static final long DEPLOY_VALUE_NANOTON = 300_000_000L;

    @Test
    @DisplayName("B10：向 testnet 真机发送 S5 部署消息（三角色皆钱包自身）")
    void deployToTestnet() throws Exception {
        byte[] seed = ChainWalletKeys.seedFromMnemonic(
                System.getenv("TGG_CHAIN_UPGRADE_WALLET_MNEMONIC"));
        long walletId = Long.parseLong(System.getenv()
                .getOrDefault("TGG_CHAIN_UPGRADE_WALLET_ID", "2147483645"));
        var keyPair = TweetNaclFast.Signature.keyPair_fromSeed(seed);
        var wallet = ChainWalletKeys.v5r1WalletAddress(seed, walletId);

        // raw 形态贯穿存储编码（形态恒定，与对拍时一致）
        String walletRaw = wallet.toRaw();
        var storage = EscrowStorageCodec.storageCell(walletRaw, walletRaw, walletRaw,
                new BigInteger("1000000000"), 0,             // amount=1 TON（仅登记）；state=OPEN
                EscrowStorageCodec.ZERO_ADDRESS, 0);          // W=零哨兵；asset=0（TON）
        var stateInit = EscrowDeployCodec.stateInit(EscrowCode.escrowCodeCell(), storage);
        var contract = EscrowDeployCodec.addressOf(stateInit);

        System.out.println("WALLET=" + wallet.toBounceableTestnet());
        System.out.println("CONTRACT=" + contract.toBounceableTestnet());
        System.out.println("CONTRACT-RAW=" + contract.toRaw());
        System.out.println("DEPLOY-VALUE-NANOTON=" + DEPLOY_VALUE_NANOTON);

        // 生产路径原样（forNetwork：ADNL 建连 + seqno runMethod + 广播）——不绕接缝，
        // 这样核销结果对 forNetwork 同样成立（其短板会在此暴露，正是核销目的）。
        try (AdnlChainSender sender = AdnlChainSender.forNetwork(
                true, walletId, keyPair, Clock.systemUTC())) {
            System.out.println("SENDER-WALLET=" + sender.walletAddress().toBounceableTestnet());
            sender.sendDeploy(contract.toRaw(), stateInit, DEPLOY_VALUE_NANOTON);
        }
        System.out.println("DEPLOY-BROADCAST=OK（广播未抛；上链确认用 acton rpc info 查 CONTRACT）");
    }
}
