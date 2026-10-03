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
package com.tg.escrow.webapp;

import com.tg.escrow.common.TggException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** TON Pay 验签（ET-32 第 1 步）的行为固定测试。 */
class TonPayWebhookVerifierTest {

    private static final String SECRET = "test-secret-abc";

    private static byte[] body(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("正确签名（自算 hex）→ 通过")
    void acceptsValidSignature() {
        TonPayWebhookVerifier verifier = new TonPayWebhookVerifier(SECRET);
        byte[] payload = body("{\"event\":\"transfer.completed\"}");

        assertThat(verifier.matches(payload, verifier.signatureOf(payload))).isTrue();
    }

    @Test
    @DisplayName("篡改 body 或签名 → 拒绝（大小写不敏感的 hex 比较）")
    void rejectsTampered() {
        TonPayWebhookVerifier verifier = new TonPayWebhookVerifier(SECRET);
        byte[] payload = body("{\"amount\":\"100\"}");
        String good = verifier.signatureOf(payload);

        assertThat(verifier.matches(body("{\"amount\":\"999\"}"), good)).isFalse();
        assertThat(verifier.matches(payload, good.toUpperCase())).as("hex 大写仍等价").isTrue();
        assertThat(verifier.matches(payload, "00".repeat(32))).isFalse();
    }

    @Test
    @DisplayName("非 hex / null / blank 签名 → 一律判否（不抛）")
    void rejectsMalformed() {
        TonPayWebhookVerifier verifier = new TonPayWebhookVerifier(SECRET);

        assertThat(verifier.matches(body("{}"), "not-a-hex")).isFalse();
        assertThat(verifier.matches(body("{}"), null)).isFalse();
        assertThat(verifier.matches(body("{}"), "  ")).isFalse();
        assertThat(verifier.matches(null, "ab")).isFalse();
    }

    @Test
    @DisplayName("构造：密钥未配置（null/blank）→ 拒绝构造（fail-closed）")
    void secretRequired() {
        assertThatThrownBy(() -> new TonPayWebhookVerifier(null))
                .isInstanceOf(TggException.class);
        assertThatThrownBy(() -> new TonPayWebhookVerifier(" "))
                .isInstanceOf(TggException.class);
    }
}
