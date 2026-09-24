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

import com.tg.escrow.common.TggException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 日志脱敏加盐（ET-57 预映像防护在 GM-33 的落地）的行为固定测试。
 *
 * <p>原 {@code maskUserId(long)} 无盐且仅 24 bit：攻击者可预计算字典反推、大用户量下还会碰撞。
 * 新增的带盐方法钉住三条：<b>盐生效</b>（不同盐不同标识）、<b>输出 48 bit</b>（碰撞空间加大）、
 * <b>盐必填</b>（fail-closed——无盐调用等于没修）。旧方法保留原行为以兼容既有调用方。
 */
class LogSanitizerSaltTest {

    @Test
    @DisplayName("盐生效：同一 ID 不同盐 → 不同标识（字典预计算失效）")
    void saltChangesOutput() {
        String a = LogSanitizer.maskUserId(1001L, "deploy-salt-A");
        String b = LogSanitizer.maskUserId(1001L, "deploy-salt-B");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("同盐同 ID 稳定映射（日志仍可关联同一用户）")
    void stableUnderSameSalt() {
        assertThat(LogSanitizer.maskUserId(1001L, "salt"))
                .isEqualTo(LogSanitizer.maskUserId(1001L, "salt"));
    }

    @Test
    @DisplayName("输出 48 bit（12 位 hex），碰撞空间比 24 bit 大 2^24 倍")
    void outputIs48Bit() {
        String masked = LogSanitizer.maskUserId(1001L, "salt");

        assertThat(masked).startsWith("u:");
        assertThat(masked.substring(2)).hasSize(12).matches("[0-9a-f]{12}");
    }

    @Test
    @DisplayName("盐为空白 / null → 抛（无盐调用等于没修，fail-closed）")
    void blankSaltRejected() {
        assertThatThrownBy(() -> LogSanitizer.maskUserId(1001L, ""))
                .isInstanceOf(TggException.class);
        assertThatThrownBy(() -> LogSanitizer.maskUserId(1001L, null))
                .isInstanceOf(TggException.class);
    }

    @Test
    @DisplayName("旧 maskUserId(long) 保留原行为（24 bit、无盐）——向后兼容不破坏")
    void legacyMethodUnchanged() {
        String masked = LogSanitizer.maskUserId(1001L);

        assertThat(masked).startsWith("u:");
        assertThat(masked.substring(2)).hasSize(6);
    }
}
