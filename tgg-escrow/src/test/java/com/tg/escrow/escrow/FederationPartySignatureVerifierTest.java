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
package com.tg.escrow.escrow;

import com.tg.escrow.common.TggException;
import com.tg.escrow.escrow.EscrowMultiSigRule.Party;
import com.tg.escrow.federation.FederationKeyPair;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 联邦方验签器（生产实现）的行为固定测试。
 *
 * <p>本类补上的是一个真实缺口：{@link EscrowVerdictGuard} 需要一个
 * {@link PartySignatureVerifier}，而在此之前**生产代码里没有任何实现**——
 * 只有测试里的 lambda 替身。规则层说"必含联邦"，接线层却是空的。
 */
class FederationPartySignatureVerifierTest {

    private static final EscrowVerdict VERDICT = new EscrowVerdict(
            7L, EscrowVerdict.Outcome.RELEASE, "已交付", Instant.parse("2026-09-23T10:00:00Z"));

    private static final FederationKeyPair FEDERATION_KEY = FederationKeyPair.fromSeed(seed(0));
    private static final FederationKeyPair IMPOSTOR_KEY = FederationKeyPair.fromSeed(seed(1));

    private static byte[] seed(int xor) {
        byte[] s = new byte[32];
        for (int i = 0; i < s.length; i++) {
            s[i] = (byte) (i ^ xor);
        }
        return s;
    }

    private static FederationPartySignatureVerifier verifier() {
        return new FederationPartySignatureVerifier(FEDERATION_KEY.publicKeyBase64());
    }

    @Test
    @DisplayName("联邦方 + 真实签名 → 通过")
    void realFederationSignaturePasses() {
        byte[] signature = FEDERATION_KEY.sign(VERDICT.canonicalBytes());

        assertThat(verifier().verify(Party.FEDERATION, VERDICT.canonicalBytes(), signature)).isTrue();
    }

    @Test
    @DisplayName("冒充者的签名 → 拒绝（验签用的是配置里的公钥，不是签名里自带的）")
    void impostorSignatureRejected() {
        byte[] forged = IMPOSTOR_KEY.sign(VERDICT.canonicalBytes());

        assertThat(verifier().verify(Party.FEDERATION, VERDICT.canonicalBytes(), forged)).isFalse();
    }

    @Test
    @DisplayName("签名与裁决内容绑定——换一份裁决即失效")
    void signatureBoundToPayload() {
        byte[] signature = FEDERATION_KEY.sign(VERDICT.canonicalBytes());
        EscrowVerdict another = new EscrowVerdict(
                7L, EscrowVerdict.Outcome.REFUND, "已交付", Instant.parse("2026-09-23T10:00:00Z"));

        assertThat(verifier().verify(Party.FEDERATION, another.canonicalBytes(), signature)).isFalse();
    }

    @Test
    @DisplayName("非联邦方一律 false——本验签器不越权代验买卖双方的签名")
    void nonFederationPartiesAlwaysRejected() {
        byte[] signature = FEDERATION_KEY.sign(VERDICT.canonicalBytes());

        // 即便签名本身有效，也不该被当作买方/卖方的签名通过：
        // 那两方的签名来自钱包（ton_proof），验证属 tgg-chain 的职责。
        assertThat(verifier().verify(Party.BUYER, VERDICT.canonicalBytes(), signature)).isFalse();
        assertThat(verifier().verify(Party.SELLER, VERDICT.canonicalBytes(), signature)).isFalse();
    }

    @Test
    @DisplayName("空签名 / null 载荷 → false，不抛异常（外部输入不可信）")
    void blankInputsRejectedWithoutException() {
        FederationPartySignatureVerifier verifier = verifier();

        assertThat(verifier.verify(Party.FEDERATION, VERDICT.canonicalBytes(), new byte[0])).isFalse();
        assertThat(verifier.verify(Party.FEDERATION, null, new byte[]{1, 2})).isFalse();
        assertThat(verifier.verify(null, VERDICT.canonicalBytes(), new byte[]{1, 2})).isFalse();
    }

    @Test
    @DisplayName("未提供联邦公钥 → 构造期抛异常（fail-fast：宁可不启动，也不要静默拒掉所有联邦签名）")
    void missingPublicKeyFailsFast() {
        assertThatThrownBy(() -> new FederationPartySignatureVerifier(null))
                .isInstanceOf(TggException.class);
        assertThatThrownBy(() -> new FederationPartySignatureVerifier("  "))
                .isInstanceOf(TggException.class);
    }

    @Test
    @DisplayName("接到裁决守卫上：只有联邦签名 → 不可执行；联邦 + 买方 → 可执行")
    void wiresIntoVerdictGuard() {
        byte[] federationSignature = FEDERATION_KEY.sign(VERDICT.canonicalBytes());

        // 只有联邦一方：不足阈值
        assertThat(EscrowVerdictGuard.isExecutable(VERDICT,
                java.util.Map.of(Party.FEDERATION, federationSignature), verifier())).isFalse();

        // 联邦 + 买方（买方签名由链上验签器负责，此处用恒真替身模拟"链上验签通过"）
        PartySignatureVerifier composite = (party, payload, signature) ->
                party == Party.FEDERATION
                        ? verifier().verify(party, payload, signature)
                        : true;

        assertThat(EscrowVerdictGuard.isExecutable(VERDICT,
                java.util.Map.of(Party.FEDERATION, federationSignature, Party.BUYER, new byte[]{9}),
                composite)).isTrue();
    }
}
