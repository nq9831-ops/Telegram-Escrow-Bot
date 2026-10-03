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
 * {@link UserIdMasker} 的行为固定测试。
 *
 * <p>钉住三件事：① 配了合用盐时它就是 {@link LogSanitizer} 的带盐版（不自成一套算法）；
 * ② <b>没配盐或盐过弱时也不照用</b>——无盐是「可预计算反推」的形态，而弱盐会让
 * 「盐后不可预计算」的论证本身失效；③ {@link UserIdMasker#scrub} 能洗掉
 * <b>文本里内嵌</b>的已知 ID（异常消息这条渠道）。
 */
class UserIdMaskerTest {

    private static final long USER = 1001L;
    /** 合用盐：不短于 {@link UserIdMasker#MIN_SALT_LENGTH}。 */
    private static final String GOOD_SALT = "deploy-salt-0123456789";

    @Test
    @DisplayName("配了合用盐 → 与 LogSanitizer 带盐版逐字一致（不自成一套算法）")
    void withSaltDelegatesToSaltedLogSanitizer() {
        UserIdMasker masker = new UserIdMasker(GOOD_SALT);

        assertThat(masker.usingEphemeralSalt()).isFalse();
        assertThat(masker.mask(USER)).isEqualTo(LogSanitizer.maskUserId(USER, GOOD_SALT));
    }

    @Test
    @DisplayName("同盐内可关联：同一 ID 恒定、不同 ID 不同")
    void stableWithinSameSalt() {
        UserIdMasker masker = new UserIdMasker(GOOD_SALT);

        assertThat(masker.mask(USER)).isEqualTo(masker.mask(USER));
        assertThat(masker.mask(USER)).isNotEqualTo(masker.mask(USER + 1));
    }

    @Test
    @DisplayName("未配盐 → 进程级随机盐；输出仍是 48 bit，不退回 24 bit 旧版")
    void missingSaltUsesEphemeralSaltWithFullWidth() {
        UserIdMasker masker = new UserIdMasker(null);

        assertThat(masker.usingEphemeralSalt()).isTrue();
        assertThat(masker.mask(USER)).startsWith("u:");
        // 无盐旧版是 6 位 hex（24 bit），带盐版是 12 位（48 bit）——用宽度钉住「没退回旧版」
        assertThat(masker.mask(USER)).hasSize("u:".length() + 12);
    }

    @Test
    @DisplayName("弱盐/空白盐不照用：短于下限归随机盐，恰好等于下限才认")
    void weakOrBlankSaltFallsBackToEphemeral() {
        assertThat(new UserIdMasker("short").usingEphemeralSalt())
                .as("弱盐照用会让「盐后不可预计算」的论证失效，故不采信")
                .isTrue();
        assertThat(new UserIdMasker("a".repeat(UserIdMasker.MIN_SALT_LENGTH - 1))
                .usingEphemeralSalt()).isTrue();
        assertThat(new UserIdMasker("   ").usingEphemeralSalt()).isTrue();
        assertThat(new UserIdMasker("a".repeat(UserIdMasker.MIN_SALT_LENGTH))
                .usingEphemeralSalt()).isFalse();
    }

    @Test
    @DisplayName("两个无盐实例的盐互不相同（确实随机）")
    void ephemeralSaltsDiffer() {
        assertThat(new UserIdMasker(null).mask(USER))
                .isNotEqualTo(new UserIdMasker(null).mask(USER));
    }

    @Test
    @DisplayName("scrub：把文本里内嵌的已知 ID 换掉——堵住异常消息这条泄露渠道")
    void scrubReplacesEmbeddedKnownIds() {
        UserIdMasker masker = new UserIdMasker(GOOD_SALT);

        String cleaned = masker.scrub("回复出口：发送文本失败（chat=2002）", 2002L);

        assertThat(cleaned).as("内嵌的裸 ID 必须被抵消，否则同一行的掩码形同虚设")
                .doesNotContain("chat=2002");
        assertThat(cleaned).contains(masker.mask(2002L));
    }

    @Test
    @DisplayName("scrub：一次可给多个 ID；null / 空文本原样返回")
    void scrubHandlesMultipleIdsAndNull() {
        UserIdMasker masker = new UserIdMasker(GOOD_SALT);

        String cleaned = masker.scrub("chat=-1001234567890 user=4242", -1001234567890L, 4242L);

        assertThat(cleaned).doesNotContain("chat=-1001234567890").doesNotContain("user=4242");
        assertThat(cleaned).contains(masker.mask(4242L));

        assertThat(masker.scrub(null, 1L)).isNull();
        assertThat(masker.scrub("", 1L)).isEmpty();
    }

    @Test
    @DisplayName("明文 ID 绝不出现在输出里")
    void neverLeaksPlaintext() {
        assertThat(new UserIdMasker(GOOD_SALT).mask(13800138000L)).doesNotContain("13800138000");
    }
}
