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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RankPrivacy}（ET-80）分支级行为固定测试。
 *
 * <p>现有 {@link RankingTest#privacyMasksByDefault} 只钉了"默认脱敏/公开显示"两分支的
 * 松断言；本类补齐其余分支与<b>精确脱敏形态</b>——脱敏串是用户可见文案，形态变化
 * （如 {@code u***A} 变 {@code u**A}）必须由测试点名，不能靠"不含原名"蒙混。
 */
class RankPrivacyTest {

    @Test
    @DisplayName("null / 空白用户名 → 固定占位 u***（优先于公开标志）")
    void blankUsernameFallsBackToPlaceholder() {
        assertThat(RankPrivacy.display(null, false)).isEqualTo("u***");
        assertThat(RankPrivacy.display(null, true)).isEqualTo("u***");
        assertThat(RankPrivacy.display("", false)).isEqualTo("u***");
        assertThat(RankPrivacy.display("   ", true)).isEqualTo("u***");
    }

    @Test
    @DisplayName("公开时显示原名（trim 后）")
    void publicProfileShowsName() {
        assertThat(RankPrivacy.display("userA", true)).isEqualTo("userA");
    }

    @Test
    @DisplayName("脱敏精确形态：长名保首尾 u***A，短名（≤2 字符）只保首字符")
    void maskedFormIsPinnedExactly() {
        assertThat(RankPrivacy.display("userA", false)).isEqualTo("u***A");
        assertThat(RankPrivacy.display("ab", false)).isEqualTo("a***");
        assertThat(RankPrivacy.display("a", false)).isEqualTo("a***");
    }

    @Test
    @DisplayName("脱敏按 trim 后计算长度与首尾（前后空白不撑长名字、不当首尾字符）")
    void maskingTrimsBeforeMeasuring() {
        // 不 trim 的话 " ab " 首尾是空格 → " *** "；trim 后 2 字符走短名分支
        assertThat(RankPrivacy.display(" a ", false)).isEqualTo("a***");
        assertThat(RankPrivacy.display(" ab ", false)).isEqualTo("a***");
        // 不 trim 的话 " abc " 会取到空格当首尾；trim 后取 a / c
        assertThat(RankPrivacy.display(" abc ", false)).isEqualTo("a***c");
    }

    @Test
    @DisplayName("中文名按 char 计首尾（BMP 字符不裂成代理对）")
    void maskingWorksOnCjkNames() {
        assertThat(RankPrivacy.display("张三", false)).isEqualTo("张***");
        assertThat(RankPrivacy.display("张三丰", false)).isEqualTo("张***丰");
    }
}
