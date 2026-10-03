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

import com.tg.escrow.escrow.EscrowMultiSigRule.Party;
import com.tg.escrow.federation.FederationKeyPair;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 裁决守卫生效路径的行为固定测试。
 *
 * <p>本类补上 {@link EscrowMultiSigRule} 的<b>前提</b>：规则判的是「已验签通过的签名方集合」，
 * 而"已验签通过"这件事必须有人真的做。若没人做、或做了但把失败方也计入，
 * 那么「裁决必含联邦」就只是注释里的一句话。
 */
class EscrowVerdictGuardTest {

    private static final EscrowVerdict VERDICT = new EscrowVerdict(
            42L, EscrowVerdict.Outcome.RELEASE, "卖方已交付", Instant.parse("2026-09-23T10:00:00Z"));

    /** 真的联邦节点密钥（固定 seed，结果可复现）。 */
    private static final FederationKeyPair FEDERATION_KEY = FederationKeyPair.fromSeed(seed(0));

    /** 另一把密钥——冒充联邦节点。 */
    private static final FederationKeyPair IMPOSTOR_KEY = FederationKeyPair.fromSeed(seed(1));

    private static byte[] seed(int xor) {
        byte[] s = new byte[32];
        for (int i = 0; i < s.length; i++) {
            s[i] = (byte) (i ^ xor);
        }
        return s;
    }

    /** 验签器：只有出现在 {@code accepting} 里的参与方签名才通过。 */
    private static PartySignatureVerifier verifierAccepting(Set<Party> accepting) {
        return (party, payload, signature) -> accepting.contains(party);
    }

    /** 真实验签器：联邦方用真公钥验，买卖方一律放行（链上验签待 TON 模块接入）。 */
    private static PartySignatureVerifier federationRealVerifier(String federationPublicKeyB64) {
        return (party, payload, signature) -> party == Party.FEDERATION
                ? FederationKeyPair.verify(payload, signature, federationPublicKeyB64)
                : true;
    }

    private static Map<Party, byte[]> signaturesOf(Party... parties) {
        Map<Party, byte[]> map = new EnumMap<>(Party.class);
        for (Party p : parties) {
            map.put(p, ("sig-of-" + p).getBytes(StandardCharsets.UTF_8));
        }
        return map;
    }

    @Test
    @DisplayName("全部验签通过时，集合如实反映签名方")
    void allVerifiedPartiesCollected() {
        Set<Party> verified = EscrowVerdictGuard.verifiedParties(
                VERDICT, signaturesOf(Party.FEDERATION, Party.BUYER),
                verifierAccepting(Set.of(Party.FEDERATION, Party.BUYER)));

        assertThat(verified).containsExactlyInAnyOrder(Party.FEDERATION, Party.BUYER);
    }

    @Test
    @DisplayName("验签失败的一方不进集合——伪造的签名不能充数")
    void failedVerificationIsExcluded() {
        Set<Party> verified = EscrowVerdictGuard.verifiedParties(
                VERDICT, signaturesOf(Party.FEDERATION, Party.BUYER),
                verifierAccepting(Set.of(Party.BUYER))); // 联邦这份验签不通过

        assertThat(verified).containsExactly(Party.BUYER);
    }

    @Test
    @DisplayName("只有联邦一方 → 不足阈值，不可执行")
    void federationAloneIsNotExecutable() {
        assertThat(EscrowVerdictGuard.isExecutable(
                VERDICT, signaturesOf(Party.FEDERATION),
                verifierAccepting(Set.of(Party.FEDERATION)))).isFalse();
    }

    @Test
    @DisplayName("联邦 + 买方 → 可执行")
    void federationWithBuyerIsExecutable() {
        assertThat(EscrowVerdictGuard.isExecutable(
                VERDICT, signaturesOf(Party.FEDERATION, Party.BUYER),
                verifierAccepting(Set.of(Party.FEDERATION, Party.BUYER)))).isTrue();
    }

    @Test
    @DisplayName("联邦 + 卖方 → 可执行")
    void federationWithSellerIsExecutable() {
        assertThat(EscrowVerdictGuard.isExecutable(
                VERDICT, signaturesOf(Party.FEDERATION, Party.SELLER),
                verifierAccepting(Set.of(Party.FEDERATION, Party.SELLER)))).isTrue();
    }

    @Test
    @DisplayName("买方 + 卖方即便都验签通过也不可执行——串通无法自行放款")
    void buyerAndSellerEvenIfVerifiedAreNotExecutable() {
        assertThat(EscrowVerdictGuard.isExecutable(
                VERDICT, signaturesOf(Party.BUYER, Party.SELLER),
                verifierAccepting(Set.of(Party.BUYER, Party.SELLER)))).isFalse();
    }

    @Test
    @DisplayName("无任何签名 → 空集合，不可执行")
    void noSignaturesIsNotExecutable() {
        assertThat(EscrowVerdictGuard.verifiedParties(VERDICT, Map.of(),
                verifierAccepting(Set.of(Party.FEDERATION)))).isEmpty();
        assertThat(EscrowVerdictGuard.isExecutable(VERDICT, Map.of(),
                verifierAccepting(Set.of(Party.FEDERATION)))).isFalse();
    }

    @Test
    @DisplayName("签名值为空/缺失的一方同样不计入（不可空手冒充签名方）")
    void blankSignatureIsExcluded() {
        Map<Party, byte[]> sigs = signaturesOf(Party.FEDERATION);
        sigs.put(Party.BUYER, new byte[0]);

        Set<Party> verified = EscrowVerdictGuard.verifiedParties(VERDICT, sigs,
                verifierAccepting(Set.of(Party.FEDERATION, Party.BUYER)));

        assertThat(verified).containsExactly(Party.FEDERATION);
    }

    @Test
    @DisplayName("真实联邦密钥签出的签名被接受")
    void realFederationSignatureAccepted() {
        byte[] payload = VERDICT.canonicalBytes();
        byte[] signature = FEDERATION_KEY.sign(payload);

        assertThat(EscrowVerdictGuard.isExecutable(VERDICT,
                Map.of(Party.FEDERATION, signature),
                federationRealVerifier(FEDERATION_KEY.publicKeyBase64()))).isFalse(); // 只有联邦，不足阈值

        Map<Party, byte[]> sigs = signaturesOf(Party.BUYER);
        sigs.put(Party.FEDERATION, signature);
        assertThat(EscrowVerdictGuard.isExecutable(VERDICT, sigs,
                federationRealVerifier(FEDERATION_KEY.publicKeyBase64()))).isTrue();
    }

    @Test
    @DisplayName("冒充联邦节点的签名被拒绝——验签用的确实是配置里的公钥")
    void forgedFederationSignatureRejected() {
        byte[] payload = VERDICT.canonicalBytes();
        // 冒充者用自己的私钥签
        byte[] forged = IMPOSTOR_KEY.sign(payload);

        Map<Party, byte[]> sigs = signaturesOf(Party.BUYER);
        sigs.put(Party.FEDERATION, forged);

        assertThat(EscrowVerdictGuard.isExecutable(VERDICT, sigs,
                federationRealVerifier(FEDERATION_KEY.publicKeyBase64()))).isFalse();
    }

    @Test
    @DisplayName("签名绑定裁决内容——同一签名换一份裁决就不通过")
    void signatureIsBoundToVerdictContent() {
        byte[] signature = FEDERATION_KEY.sign(VERDICT.canonicalBytes());

        EscrowVerdict another = new EscrowVerdict(
                42L, EscrowVerdict.Outcome.REFUND, "卖方已交付",
                Instant.parse("2026-09-23T10:00:00Z"));

        Map<Party, byte[]> sigs = signaturesOf(Party.BUYER);
        sigs.put(Party.FEDERATION, signature);

        assertThat(EscrowVerdictGuard.isExecutable(another, sigs,
                federationRealVerifier(FEDERATION_KEY.publicKeyBase64()))).isFalse();
    }
}
