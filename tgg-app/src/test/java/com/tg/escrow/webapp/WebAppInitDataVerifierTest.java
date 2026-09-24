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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Telegram Mini App {@code initData} 验签的行为固定测试。
 *
 * <h2>算法出处（官方原文，非第三方教程）</h2>
 * <p>来自 {@code core.telegram.org/bots/webapps}（2026-09-24 由服务器直取原文）：
 * <blockquote>
 * "Data-check-string is a chain of <b>all received fields</b>, sorted alphabetically,
 * in the format {@code key=<value>} with a line feed character (0x0A) used as separator"
 * <br>"{@code secret_key = HMAC_SHA256(<bot_token>, "WebAppData")}"
 * <br>"{@code if (hex(HMAC_SHA256(data_check_string, secret_key)) == hash)}"
 * <br>"To prevent the use of outdated data, you can additionally check the {@code auth_date} field"
 * </blockquote>
 *
 * <h2>两套 data-check-string 的区分（易错点）</h2>
 * <p>官方另有「第三方 Ed25519 验签」段落，那套串要 **prepend {@code bot_id:WebAppData}
 * 前缀**、且排除 {@code hash} **与 {@code signature}**——与持有 token 时的 hash 验签
 * <b>不是同一个串</b>。本类测的是后者（我们持有 bot token）。
 *
 * <h2>测试向量的来源（诚实标注）</h2>
 * <p>向量由本测试按上述官方算法<b>自算</b>（{@link #sign}）——因此它证明的是
 * 「实现符合官方规格」，<b>不是</b>「与 Telegram 真实产出逐字节一致」。后者需真机
 * initData，属线上验证项（见 {@code docs/ONLINE-VERIFICATION.md}）。
 */
class WebAppInitDataVerifierTest {

    private static final String BOT_TOKEN = "123456:TEST-TOKEN-abc";
    private static final Instant T0 = Instant.parse("2026-09-24T12:00:00Z");
    private static final Duration MAX_AGE = Duration.ofHours(1);

    /** 按官方算法自算 hash（测试向量生成；勿在生产代码复用）。 */
    private static String sign(String dataCheckString) {
        try {
            Mac outer = Mac.getInstance("HmacSHA256");
            outer.init(new SecretKeySpec("WebAppData".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] secret = outer.doFinal(BOT_TOKEN.getBytes(StandardCharsets.UTF_8));

            Mac inner = Mac.getInstance("HmacSHA256");
            inner.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(inner.doFinal(dataCheckString.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 造一个结构合法的 initData（字段按 key 排序后拼串算 hash）。 */
    private static String initData(long userId, Instant authDate) {
        List<String> fields = new ArrayList<>(List.of(
                "auth_date=" + authDate.getEpochSecond(),
                "query_id=AAHdF6IQAAAAAN0XohDhrOrc",
                "user=" + URLEncoder.encode(
                        "{\"id\":" + userId + ",\"first_name\":\"Tester\",\"username\":\"tester\"}",
                        StandardCharsets.UTF_8)));
        fields.sort(String::compareTo);   // 官方：sorted alphabetically
        String dcs = String.join("\n", fields);
        return String.join("&", fields) + "&hash=" + sign(dcs);
    }

    /** 造一个带 {@code start_param} 的合法 initData（模拟经 {@code ?startapp=<token>} 打开）。 */
    private static String initDataWithStartParam(long userId, Instant authDate, String startParam) {
        List<String> fields = new ArrayList<>(List.of(
                "auth_date=" + authDate.getEpochSecond(),
                "query_id=AAHdF6IQAAAAAN0XohDhrOrc",
                "start_param=" + startParam,
                "user=" + URLEncoder.encode(
                        "{\"id\":" + userId + ",\"first_name\":\"Tester\",\"username\":\"tester\"}",
                        StandardCharsets.UTF_8)));
        fields.sort(String::compareTo);
        String dcs = String.join("\n", fields);
        return String.join("&", fields) + "&hash=" + sign(dcs);
    }

    private static WebAppInitDataVerifier verifier(Instant now) {
        return new WebAppInitDataVerifier(BOT_TOKEN, MAX_AGE, Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("验签通过 → 返回可信 userId")
    void validInitDataYieldsUserId() {
        String data = initData(8724975623L, T0);

        assertThat(verifier(T0).verifyUserId(data)).hasValue(8724975623L);
    }

    @Test
    @DisplayName("篡改任一字段 → 拒绝（签名不再匹配）")
    void tamperedFieldRejected() {
        String data = initData(8724975623L, T0).replace("auth_date=", "auth_date=1");

        assertThat(verifier(T0).verifyUserId(data)).isEmpty();
    }

    @Test
    @DisplayName("换掉 user 字段（伪造他人身份）→ 拒绝")
    void forgedUserRejected() {
        String data = initData(8724975623L, T0).replace("8724975623", "9999999999");

        assertThat(verifier(T0).verifyUserId(data)).isEmpty();
    }

    @Test
    @DisplayName("start_param 随签名下发 → verify 同时取出 userId 与令牌")
    void startParamExtractedFromSignedData() {
        String data = initDataWithStartParam(8724975623L, T0, "abc123token");

        var verified = verifier(T0).verify(data);

        assertThat(verified).isPresent();
        assertThat(verified.get().userId()).isEqualTo(8724975623L);
        assertThat(verified.get().startParam()).isEqualTo("abc123token");
    }

    @Test
    @DisplayName("篡改 start_param（不改 hash）→ 验签失败——身份与令牌同源于签名串")
    void tamperedStartParamRejected() {
        String data = initDataWithStartParam(8724975623L, T0, "abc123token")
                .replace("abc123token", "evil-token-9");

        assertThat(verifier(T0).verify(data))
                .as("若实现从 initDataUnsafe（未签名）取令牌，此篡改会被放行——正是本用例要证伪的")
                .isEmpty();
    }

    @Test
    @DisplayName("无 start_param 的普通入口 → userId 可取，令牌为 null（而非空串）")
    void missingStartParamYieldsNull() {
        var verified = verifier(T0).verify(initData(8724975623L, T0));

        assertThat(verified).isPresent();
        assertThat(verified.get().startParam()).isNull();
    }

    @Test
    @DisplayName("过期 auth_date → 拒绝（防重放；官方明示此项）")
    void staleAuthDateRejected() {
        String data = initData(8724975623L, T0.minus(Duration.ofHours(3)));

        assertThat(verifier(T0).verifyUserId(data)).isEmpty();
    }

    @Test
    @DisplayName("缺 hash 字段 → 拒绝（不 fail-open）")
    void missingHashRejected() {
        String data = initData(8724975623L, T0);
        String withoutHash = data.substring(0, data.indexOf("&hash="));

        assertThat(verifier(T0).verifyUserId(withoutHash)).isEmpty();
    }

    @Test
    @DisplayName("空 / null / 畸形输入 → 拒绝，且不抛异常（fail-closed）")
    void malformedInputsRejectedQuietly() {
        WebAppInitDataVerifier v = verifier(T0);

        assertThat(v.verifyUserId(null)).isEmpty();
        assertThat(v.verifyUserId("")).isEmpty();
        assertThat(v.verifyUserId("garbage")).isEmpty();
        assertThat(v.verifyUserId("hash=deadbeef")).isEmpty();
    }

    @Test
    @DisplayName("auth_date 非数字 → 拒绝（不因解析异常而放行）")
    void nonNumericAuthDateRejected() {
        String data = initData(8724975623L, T0).replace(
                "auth_date=" + T0.getEpochSecond(), "auth_date=abc");

        assertThat(verifier(T0).verifyUserId(data)).isEmpty();
    }

    @Test
    @DisplayName("构造：token 或时钟缺失 → 抛（fail-fast，不静默裸奔）")
    void ctorRejectsMissingDeps() {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> new WebAppInitDataVerifier(null, MAX_AGE, Clock.systemUTC())))
                .isInstanceOf(com.tg.escrow.common.TggException.class);
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> new WebAppInitDataVerifier(BOT_TOKEN, MAX_AGE, null)))
                .isInstanceOf(com.tg.escrow.common.TggException.class);
    }
}
