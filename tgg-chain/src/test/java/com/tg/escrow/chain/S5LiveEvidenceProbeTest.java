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
import org.ton.ton4j.cell.Cell;

import java.math.BigInteger;
import java.time.Clock;

/**
 * B11 真机核销运行器：向已部署的演练合约发送链上存证（{@code RecordEvidence}）。
 *
 * <h2>验证（发送后单独执行，见 ONLINE-VERIFICATION B11）</h2>
 * <pre>{@code
 * acton rpc call kQAnbsVohZ0FmcDNrXfcDdXKv8MzsicRRlQdDcZvmPAvTQFk evidenceCount
 * acton rpc call kQAnbsVohZ0FmcDNrXfcDdXKv8MzsicRRlQdDcZvmPAvTQFk evidenceOf <EVIDENCE-HASH-DEC>
 * }</pre>
 * 期望：count ≥ 1；evidenceOf 返回 found=1 且时刻非零。
 *
 * <p>启用（双闸同 S5LiveDeployProbeTest）：{@code TGG_LIVE_DEPLOY=true} + 助记词环境变量。
 */
@EnabledIfEnvironmentVariable(named = "TGG_CHAIN_UPGRADE_WALLET_MNEMONIC", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TGG_LIVE_DEPLOY", matches = "true")
class S5LiveEvidenceProbeTest {

    /** 固定证据哈希（便于用 acton rpc call 复读；十进制值打印在输出里）。 */
    private static final BigInteger EVIDENCE_HASH = new BigInteger("b11b11b11b11", 16);

    /** 演练合约（B10 部署产物；raw 形态）。 */
    private static final String CONTRACT_RAW =
            "0:276ec568859d0599c0cdad77dc0dd5cabfc333b2271146541d0dc66f98f02f4d";

    @Test
    @DisplayName("B11：链上存证真机——RecordEvidence 发往演练合约（读回用 acton rpc call）")
    void recordEvidenceToLiveContract() throws Exception {
        byte[] seed = ChainWalletKeys.seedFromMnemonic(
                System.getenv("TGG_CHAIN_UPGRADE_WALLET_MNEMONIC"));
        long walletId = Long.parseLong(System.getenv()
                .getOrDefault("TGG_CHAIN_UPGRADE_WALLET_ID", "2147483645"));
        var keyPair = TweetNaclFast.Signature.keyPair_fromSeed(seed);

        Cell body = RecordEvidenceMessageCodec.record(EVIDENCE_HASH);
        System.out.println("EVIDENCE-HASH-DEC=" + EVIDENCE_HASH);

        try (AdnlChainSender sender = AdnlChainSender.forNetwork(
                true, walletId, keyPair, Clock.systemUTC())) {
            sender.sendToContract(CONTRACT_RAW, body, 200_000_000L);
        }
        System.out.println("EVIDENCE-BROADCAST=OK（读回：acton rpc call <CONTRACT> evidenceOf "
                + EVIDENCE_HASH + "）");
    }
}
