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
package com.tg.escrow.federation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 联邦节点密钥与签名验签的行为固定测试。
 *
 * <p>这是担保交易裁决的信任根基：{@code EscrowMultiSigRule} 判定的是「签名方集合」，
 * 而集合的每个成员都<b>必须先通过验签</b>才算数。若验签可被伪造或被静默跳过，
 * 「裁决必含联邦」这条规则就只是一句口号。
 *
 * <p>纯 POJO 测试：不启动 Spring 上下文。密钥来自固定 seed，故结果可复现。
 */
class FederationKeyPairTest {

    private static final String PAYLOAD = "order=42;action=RELEASE";

    /** 固定的 32 字节 seed（0..31）。用固定值而非随机，保证测试可复现。 */
    private static byte[] fixedSeed() {
        byte[] seed = new byte[32];
        for (int i = 0; i < seed.length; i++) {
            seed[i] = (byte) i;
        }
        return seed;
    }

    private static byte[] payload() {
        return PAYLOAD.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("同一 seed 恢复出同一公钥——密钥是确定性的，不是每次随机生成")
    void sameSeedYieldsSamePublicKey() {
        FederationKeyPair a = FederationKeyPair.fromSeed(fixedSeed());
        FederationKeyPair b = FederationKeyPair.fromSeed(fixedSeed());

        assertThat(a.publicKeyBase64()).isEqualTo(b.publicKeyBase64());
        assertThat(a.publicKeyBase64()).isNotBlank();
    }

    @Test
    @DisplayName("Base64 seed 与字节 seed 等价（配置注入走 Base64）")
    void base64SeedIsEquivalent() {
        String seedB64 = Base64.getEncoder().encodeToString(fixedSeed());

        FederationKeyPair fromBytes = FederationKeyPair.fromSeed(fixedSeed());
        FederationKeyPair fromBase64 = FederationKeyPair.fromSeedBase64(seedB64);

        assertThat(fromBase64.publicKeyBase64()).isEqualTo(fromBytes.publicKeyBase64());
    }

    @Test
    @DisplayName("签名后验签通过")
    void signThenVerifyPasses() {
        FederationKeyPair key = FederationKeyPair.fromSeed(fixedSeed());

        byte[] signature = key.sign(payload());

        assertThat(signature).isNotEmpty();
        assertThat(key.verify(payload(), signature)).isTrue();
    }

    @Test
    @DisplayName("签名是确定性的——同一 payload 两次签名结果相同（Ed25519 无随机数）")
    void signatureIsDeterministic() {
        FederationKeyPair key = FederationKeyPair.fromSeed(fixedSeed());

        assertThat(key.sign(payload())).isEqualTo(key.sign(payload()));
    }

    @Test
    @DisplayName("用公钥字符串独立验签通过（接收方只有公钥，没有私钥）")
    void staticVerifyWithPublicKeyOnly() {
        FederationKeyPair key = FederationKeyPair.fromSeed(fixedSeed());
        byte[] signature = key.sign(payload());

        // 接收方的视角：它只从对端清单里拿到公钥 Base64
        String publicKeyB64 = key.publicKeyBase64();

        assertThat(FederationKeyPair.verify(payload(), signature, publicKeyB64)).isTrue();
    }

    @Test
    @DisplayName("payload 被篡改 → 验签失败（不抛异常，返回 false）")
    void tamperedPayloadFailsVerification() {
        FederationKeyPair key = FederationKeyPair.fromSeed(fixedSeed());
        byte[] signature = key.sign(payload());

        byte[] tampered = "order=42;action=REFUND".getBytes(StandardCharsets.UTF_8);

        assertThat(key.verify(tampered, signature)).isFalse();
        assertThat(FederationKeyPair.verify(tampered, signature, key.publicKeyBase64())).isFalse();
    }

    @Test
    @DisplayName("签名被篡改 → 验签失败")
    void tamperedSignatureFailsVerification() {
        FederationKeyPair key = FederationKeyPair.fromSeed(fixedSeed());
        byte[] signature = key.sign(payload());
        signature[0] ^= 0x01;

        assertThat(key.verify(payload(), signature)).isFalse();
    }

    @Test
    @DisplayName("另一个密钥对的签名 → 用本密钥验签失败（不可跨节点冒签）")
    void signatureFromAnotherKeyFails() {
        FederationKeyPair mine = FederationKeyPair.fromSeed(fixedSeed());

        byte[] otherSeed = fixedSeed();
        otherSeed[0] = (byte) 0xFF; // 换一把种子
        FederationKeyPair other = FederationKeyPair.fromSeed(otherSeed);

        assertThat(other.publicKeyBase64()).isNotEqualTo(mine.publicKeyBase64());
        assertThat(mine.verify(payload(), other.sign(payload()))).isFalse();
    }

    @Test
    @DisplayName("多次篡改签名位置都应失败——不只是首字节")
    void tamperingAnySignatureByteFails() {
        FederationKeyPair key = FederationKeyPair.fromSeed(fixedSeed());
        byte[] signature = key.sign(payload());

        for (int i = 0; i < signature.length; i++) {
            byte[] copy = signature.clone();
            copy[i] ^= 0x01;
            assertThat(key.verify(payload(), copy))
                    .as("第 %d 字节被翻转后仍验签通过", i)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("seed 长度非法 → 构造期即抛 FederationException（fail-fast，不延迟到签名才爆）")
    void invalidSeedLengthFailsFast() {
        assertThatThrownBy(() -> FederationKeyPair.fromSeed(new byte[16]))
                .isInstanceOf(FederationException.class);

        assertThatThrownBy(() -> FederationKeyPair.fromSeed(null))
                .isInstanceOf(FederationException.class);
    }

    @Test
    @DisplayName("Base64 非法或长度不对 → 抛 FederationException")
    void invalidBase64FailsFast() {
        assertThatThrownBy(() -> FederationKeyPair.fromSeedBase64("not-base64!!"))
                .isInstanceOf(FederationException.class);

        // 合法 Base64 但只有 16 字节
        String shortSeed = Base64.getEncoder().encodeToString(new byte[16]);
        assertThatThrownBy(() -> FederationKeyPair.fromSeedBase64(shortSeed))
                .isInstanceOf(FederationException.class);
    }

    @Test
    @DisplayName("公钥字符串非法 → 静态验签返回 false 而非抛异常（对端数据不可信，不该让调用方崩）")
    void invalidPublicKeyReturnsFalse() {
        FederationKeyPair key = FederationKeyPair.fromSeed(fixedSeed());
        byte[] signature = key.sign(payload());

        assertThat(FederationKeyPair.verify(payload(), signature, "not-a-public-key")).isFalse();
        assertThat(FederationKeyPair.verify(payload(), signature, null)).isFalse();
    }

    @Test
    @DisplayName("空 payload 可以签名与验签（不假设载荷非空）")
    void emptyPayloadIsSupported() {
        FederationKeyPair key = FederationKeyPair.fromSeed(fixedSeed());
        byte[] empty = new byte[0];

        assertThat(key.verify(empty, key.sign(empty))).isTrue();
    }
}
