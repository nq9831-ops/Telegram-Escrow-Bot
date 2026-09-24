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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 欢迎模板（G11）的行为固定测试。
 *
 * <p>关键约束是<b>不泄漏模板语法</b>：变量缺失时若原样留下 {@code {username}}，
 * 群里所有人都会看到这串占位符——比少一个名字难看得多。所以缺失一律替换掉。
 */
class WelcomeTemplateTest {

    @Test
    @DisplayName("已知变量被替换（支持中文变量名与值）")
    void replacesKnownVars() {
        WelcomeTemplate t = new WelcomeTemplate("欢迎 {username} 加入 {group}");

        assertThat(t.render(Map.of("username", "小明", "group", "担保群")))
                .isEqualTo("欢迎 小明 加入 担保群");
    }

    @Test
    @DisplayName("缺失变量不留 {…} 原文（保守替换，不泄漏模板语法）")
    void missingVarDoesNotLeakPlaceholder() {
        WelcomeTemplate t = new WelcomeTemplate("欢迎 {username} 加入");

        String out = t.render(Map.of());

        assertThat(out).doesNotContain("{").doesNotContain("}");
    }

    @Test
    @DisplayName("同一变量多次出现全部替换")
    void replacesAllOccurrences() {
        WelcomeTemplate t = new WelcomeTemplate("{n} 你好，{n}");

        assertThat(t.render(Map.of("n", "A"))).isEqualTo("A 你好，A");
    }

    @Test
    @DisplayName("无变量的模板原样返回")
    void noVarsReturnsAsIs() {
        WelcomeTemplate t = new WelcomeTemplate("欢迎加入本群");

        assertThat(t.render(Map.of("x", "y"))).isEqualTo("欢迎加入本群");
    }

    @Test
    @DisplayName("null vars 视为空，但不抛异常")
    void nullVarsTreatedAsEmpty() {
        WelcomeTemplate t = new WelcomeTemplate("欢迎 {username}");

        assertThat(t.render(null)).doesNotContain("{");
    }

    @Test
    @DisplayName("空模板 → 构造期拒绝")
    void blankTemplateRejected() {
        assertThatThrownBy(() -> new WelcomeTemplate("")).isInstanceOf(TggException.class);
        assertThatThrownBy(() -> new WelcomeTemplate("   ")).isInstanceOf(TggException.class);
        assertThatThrownBy(() -> new WelcomeTemplate(null)).isInstanceOf(TggException.class);
    }
}
