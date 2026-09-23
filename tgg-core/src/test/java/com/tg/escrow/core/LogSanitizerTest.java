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
package com.tg.escrow.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 日志脱敏的行为固定测试（原文档 G24）。
 *
 * <p>脱敏的危险在于<b>看起来做了</b>：一个只替换了常见位置的实现会让人以为安全了，
 * 而实际日志里仍留着完整凭据。所以这里逐条钉住"怎么算脱敏成功"——
 * 尤其是"原文不残留"这一条，它比"包含掩码符号"严格得多。
 */
class LogSanitizerTest {

    /** 形状逼真的 Telegram Bot Token（不是真 token）。 */
    private static final String TOKEN =
            "7654321:AAF-DUMMY_TOKEN_FOR_TESTS_0123456789";

    @Test
    @DisplayName("文本中的 Bot Token 被掩码，且原文完全不残留")
    void tokenIsMaskedAndOriginalDoesNotRemain() {
        String masked = LogSanitizer.maskTokens("调用失败 token=" + TOKEN + " 结束");

        assertThat(masked).doesNotContain(TOKEN);
        assertThat(masked).doesNotContain("AAF-DUMMY_TOKEN_FOR_TESTS_0123456789");
        assertThat(masked).contains("结束"); // 非敏感内容保留
    }

    @Test
    @DisplayName("URL 里的 Token 同样被掩码（真实日志里最常见的泄露点）")
    void tokenInsideUrlIsMasked() {
        String url = "https://api.telegram.org/bot" + TOKEN + "/getMe";

        String masked = LogSanitizer.maskTokens(url);

        assertThat(masked).doesNotContain("AAF-DUMMY_TOKEN_FOR_TESTS_0123456789");
        assertThat(masked).contains("api.telegram.org");
        assertThat(masked).contains("getMe");
    }

    @Test
    @DisplayName("同一文本里多个 Token 全部被掩码")
    void multipleTokensAllMasked() {
        String other = "1111111:BBG_ANOTHER_DUMMY_TOKEN_9876543210";
        String masked = LogSanitizer.maskTokens("a=" + TOKEN + " b=" + other);

        assertThat(masked).doesNotContain("AAF-DUMMY_TOKEN_FOR_TESTS_0123456789");
        assertThat(masked).doesNotContain("BBG_ANOTHER_DUMMY_TOKEN_9876543210");
    }

    @Test
    @DisplayName("不含 Token 的文本原样返回——脱敏不该顺手改动无关内容")
    void plainTextIsUnchanged() {
        assertThat(LogSanitizer.maskTokens("用户加入群组，订单 42 已锁定"))
                .isEqualTo("用户加入群组，订单 42 已锁定");
    }

    @Test
    @DisplayName("形似但不构成 Token 的字符串不被误伤（避免把普通日志改得无法阅读）")
    void lookalikeButNotTokenIsUntouched() {
        // 缺冒号、或密钥段过短，都不是 token
        assertThat(LogSanitizer.maskTokens("时间 12:34:56 处理完成")).isEqualTo("时间 12:34:56 处理完成");
        assertThat(LogSanitizer.maskTokens("id=123:abc")).isEqualTo("id=123:abc");
    }

    @Test
    @DisplayName("null / 空文本 → 原样返回，不抛异常（日志路径不该因脱敏而崩）")
    void nullAndEmptyAreSafe() {
        assertThat(LogSanitizer.maskTokens(null)).isNull();
        assertThat(LogSanitizer.maskTokens("")).isEmpty();
    }

    @Test
    @DisplayName("用户 ID 掩码：同一 ID 恒定，可关联日志")
    void userIdMaskIsStable() {
        assertThat(LogSanitizer.maskUserId(1001L))
                .isEqualTo(LogSanitizer.maskUserId(1001L));
    }

    @Test
    @DisplayName("用户 ID 掩码：输出不含原 ID 数字（不可反查）")
    void userIdMaskHidesOriginalDigits() {
        String masked = LogSanitizer.maskUserId(13800138000L);

        assertThat(masked).doesNotContain("13800138000");
        assertThat(masked).doesNotContain("1380013");
    }

    @Test
    @DisplayName("不同用户 ID → 不同掩码（否则无法区分是哪个用户的事件）")
    void differentUserIdsYieldDifferentMasks() {
        assertThat(LogSanitizer.maskUserId(1001L))
                .isNotEqualTo(LogSanitizer.maskUserId(1002L));
    }

    @Test
    @DisplayName("用户 ID 掩码带固定前缀，便于在日志中辨认这是被脱敏的标识")
    void userIdMaskHasRecognizablePrefix() {
        assertThat(LogSanitizer.maskUserId(1001L)).startsWith("u:");
    }

    @Test
    @DisplayName("脱敏后的用户 ID 与原始数值不可通过简单换算对应（负值与零也不崩）")
    void edgeUserIdsAreHandled() {
        assertThat(LogSanitizer.maskUserId(0L)).startsWith("u:");
        assertThat(LogSanitizer.maskUserId(-1L)).startsWith("u:");
        assertThat(LogSanitizer.maskUserId(0L)).isNotEqualTo(LogSanitizer.maskUserId(-1L));
    }
}
