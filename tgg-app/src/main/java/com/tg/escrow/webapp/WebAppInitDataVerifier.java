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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tg.escrow.common.TggException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Telegram Mini App {@code initData} 验签器——<b>Mini App 的安全命门</b>。
 *
 * <h2>为什么必须有它</h2>
 * <p>前端传来的 {@code initData} 里带着 {@code user.id}，但**字符串本身是可以伪造的**：
 * 不验签就等于"谁都能声称自己是任意用户"，直接击穿交易系统的身份前提。
 * 验签证明「这串数据确由 Telegram 签发、且未被篡改」。
 *
 * <h2>算法（官方原文出处）</h2>
 * <p>{@code core.telegram.org/bots/webapps}（2026-09-24 直取原文）：
 * <ol>
 *   <li>{@code data_check_string} = 除 {@code hash} 外的全部字段，<b>按 key 字典序排序</b>，
 *       以 {@code key=<value>} 形式用换行符（0x0A）连接；</li>
 *   <li>{@code secret_key = HMAC_SHA256(key="WebAppData", msg=bot_token)}；</li>
 *   <li>比对 {@code hex(HMAC_SHA256(key=secret_key, msg=data_check_string))} 与 {@code hash}。</li>
 * </ol>
 *
 * <h2>三处易错点</h2>
 * <ul>
 *   <li><b>secret_key 方向</b>：官方是 {@code HMAC(bot_token, "WebAppData")}——字面量
 *       {@code WebAppData} 是 <b>key</b>、token 是 <b>message</b>。很多教程误写成
 *       {@code SHA256(bot_token)}（那是 Login Widget 的派생法），写错会恒验不过；</li>
 *   <li><b>另一套串</b>：官方「第三方 Ed25519 验签」那段要 prepend {@code bot_id:WebAppData}
 *       前缀并排除 {@code hash} 与 {@code signature}——<b>和本类用的不是同一个串</b>；</li>
 *   <li><b>值用原样（URL-encoded）</b>：Telegram 对原始串算 hash，故拼接时 <b>不解码</b>；
 *       仅在提取 {@code user} JSON 时才解码。</li>
 * </ul>
 *
 * <h2>失败方向</h2>
 * <p>任何异常情况（缺 hash、格式错、解析失败、过期）一律返回 {@link Optional#empty()}
 * ——<b>fail-closed</b>，绝不因"没看懂"而放行。比较用 {@link MessageDigest#isEqual}（常量时间），
 * 避免逐字节比较泄漏签名。</p>
 */
public final class WebAppInitDataVerifier {

    private static final String HMAC_ALGO = "HmacSHA256";
    private static final String SECRET_KEY_CONSTANT = "WebAppData";

    private final String botToken;
    private final Duration maxAge;
    private final Clock clock;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * @param botToken bot token（非空）
     * @param maxAge   {@code auth_date} 允许的最大年龄（防重放；正数）
     * @param clock    时钟（可测）
     */
    public WebAppInitDataVerifier(String botToken, Duration maxAge, Clock clock) {
        if (botToken == null || botToken.isBlank()) {
            throw new TggException("Mini App 验签：bot token 不可为空");
        }
        if (maxAge == null || maxAge.isZero() || maxAge.isNegative()) {
            throw new TggException("Mini App 验签：有效期必须为正，实为 " + maxAge);
        }
        if (clock == null) {
            throw new TggException("Mini App 验签：时钟不可为空");
        }
        this.botToken = botToken;
        this.maxAge = maxAge;
        this.clock = clock;
    }

    /**
     * 验签并提取可信的用户 ID。
     *
     * @param initData 前端 {@code Telegram.WebApp.initData} 的<b>原始串</b>
     * @return 验签通过时的 userId；任何失败均为 {@link Optional#empty()}
     */
    public Optional<Long> verifyUserId(String initData) {
        if (initData == null || initData.isBlank()) {
            return Optional.empty();
        }
        try {
            Map<String, String> fields = parseFields(initData);
            String receivedHash = fields.remove("hash");
            if (receivedHash == null || receivedHash.isBlank()) {
                return Optional.empty();
            }
            if (!hashMatches(fields, receivedHash)) {
                return Optional.empty();
            }
            if (!authDateFresh(fields.get("auth_date"))) {
                return Optional.empty();
            }
            return extractUserId(fields.get("user"));
        } catch (Exception ex) {
            // fail-closed：解析异常不得变成放行
            return Optional.empty();
        }
    }

    /** 解析 query string，<b>保留原始（未解码）值</b>——hash 是对原始串算的。 */
    private static Map<String, String> parseFields(String initData) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String pair : initData.split("&")) {
            if (pair.isBlank()) {
                continue;
            }
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            fields.put(pair.substring(0, eq), pair.substring(eq + 1));
        }
        return fields;
    }

    /** 官方算法：排序拼接 → 双层 HMAC → 常量时间比较。 */
    private boolean hashMatches(Map<String, String> fields, String receivedHash) throws Exception {
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, String> e : fields.entrySet()) {
            lines.add(e.getKey() + "=" + e.getValue());
        }
        lines.sort(String::compareTo);                       // sorted alphabetically
        String dataCheckString = String.join("\n", lines);   // line feed separator

        Mac outer = Mac.getInstance(HMAC_ALGO);
        outer.init(new SecretKeySpec(SECRET_KEY_CONSTANT.getBytes(StandardCharsets.UTF_8), HMAC_ALGO));
        byte[] secretKey = outer.doFinal(botToken.getBytes(StandardCharsets.UTF_8));

        Mac inner = Mac.getInstance(HMAC_ALGO);
        inner.init(new SecretKeySpec(secretKey, HMAC_ALGO));
        String computed = HexFormat.of().formatHex(
                inner.doFinal(dataCheckString.getBytes(StandardCharsets.UTF_8)));

        // 常量时间比较：String.equals 会短路过早返回，泄漏签名匹配长度
        return MessageDigest.isEqual(computed.getBytes(StandardCharsets.UTF_8),
                receivedHash.getBytes(StandardCharsets.UTF_8));
    }

    /** 官方明示：检查 auth_date 以防陈旧数据被重放。 */
    private boolean authDateFresh(String authDate) {
        if (authDate == null || authDate.isBlank()) {
            return false;
        }
        long issued;
        try {
            issued = Long.parseLong(authDate.trim());
        } catch (NumberFormatException ex) {
            return false;
        }
        Instant now = clock.instant();
        Instant issuedAt = Instant.ofEpochSecond(issued);
        // 过旧 → 拒绝；未来时刻 → 同样拒绝（时钟异常/伪造，不给宽限）
        return !issuedAt.isBefore(now.minus(maxAge)) && !issuedAt.isAfter(now.plus(maxAge));
    }

    /** user 是 URL-encoded 的 JSON，此处才解码。 */
    private Optional<Long> extractUserId(String encodedUser) {
        if (encodedUser == null || encodedUser.isBlank()) {
            return Optional.empty();
        }
        try {
            String json = URLDecoder.decode(encodedUser, StandardCharsets.UTF_8);
            JsonNode node = objectMapper.readTree(json);
            JsonNode id = node.get("id");
            return id == null || !id.canConvertToLong()
                    ? Optional.empty()
                    : Optional.of(id.asLong());
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    /** 供测试/诊断：算法用到的字面量（勿在生产逻辑里另写一份）。 */
    static List<String> algorithmConstants() {
        return Arrays.asList(HMAC_ALGO, SECRET_KEY_CONSTANT);
    }
}
