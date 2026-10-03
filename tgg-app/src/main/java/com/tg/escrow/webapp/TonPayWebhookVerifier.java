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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * TON Pay webhook 验签（ET-32 · 六步验证的第 1 步）。
 *
 * <p><b>规格</b>（`docs/requirements/03-TON-Pay与Tolk.md` §1.2，已经独立核实）：
 * {@code X-TonPay-Signature} 头、HMAC-SHA256、密钥 {@code TONPAY_API_SECRET}。
 *
 * <h2>签名原文口径（⚠ 待真机核销）</h2>
 * <p>03 文档未写明 HMAC 覆盖的原文拼接格式（raw body？含时间戳前缀？）——本实现按
 * <b>最常见形态：raw body 原文的 HMAC-SHA256（hex 比较）</b>。拿到 API Key 后用一条
 * 真实 webhook 核销：若签名不匹配，只需按 TON Pay 实际拼接改 {@link #signatureOf(byte[])}
 * 一处（六步流程的其余部分不受影响）。核销项登记于 ONLINE-VERIFICATION。
 *
 * <h2>恒定时间比较</h2>
 * <p>用 {@link MessageDigest#isEqual}（恒定时间）——防时序侧信道；非 hex 输入直接判否不抛。
 */
public final class TonPayWebhookVerifier {

    private final byte[] secret;

    public TonPayWebhookVerifier(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new TggException("TON Pay 验签：密钥未配置");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    /** 恒定时间校验（hex 签名，大小写不敏感；null/非 hex 一律判否）。 */
    boolean matches(byte[] body, String signatureHex) {
        if (body == null || signatureHex == null || signatureHex.isBlank()) {
            return false;
        }
        byte[] provided;
        try {
            provided = HexFormat.of().parseHex(signatureHex.trim());
        } catch (IllegalArgumentException ex) {
            return false;
        }
        return MessageDigest.isEqual(hmacOf(body), provided);
    }

    /** 期望签名的 hex（供测试/调试展示；比较走 {@link #hmacOf} 原始字节）。 */
    String signatureOf(byte[] body) {
        return HexFormat.of().formatHex(hmacOf(body));
    }

    /** 期望签名的原始字节——恒定时间比较的另一半。 */
    private byte[] hmacOf(byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(body);
        } catch (Exception ex) {
            throw new IllegalStateException("HMAC-SHA256 不可用", ex);
        }
    }
}
