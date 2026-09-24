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

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 关键词自动回复（GM-17）的行为固定测试。
 *
 * <p>匹配语义与 {@code BannedWordMatcher} 同族：子串命中、大小写不敏感（{@code ROOT} locale）；
 * 命中<b>首个</b>规则即回（顺序稳定可复现）。空白关键词拒绝（空串会命中一切）。
 */
class KeywordAutoReplyTest {

    @Test
    @DisplayName("关键词命中 → 返回对应回复")
    void keywordHitReturnsReply() {
        KeywordAutoReply r = new KeywordAutoReply();
        r.register("怎么收费", "平台费默认 0%，详见帮助。");

        assertThat(r.replyFor("请问 怎么收费 呢"))
                .hasValue("平台费默认 0%，详见帮助。");
    }

    @Test
    @DisplayName("大小写不敏感、命中首个规则（顺序稳定）")
    void caseInsensitiveFirstWins() {
        KeywordAutoReply r = new KeywordAutoReply();
        r.register("Hello", "第一条");
        r.register("hello world", "第二条");

        assertThat(r.replyFor("HELLO WORLD")).hasValue("第一条");
    }

    @Test
    @DisplayName("未命中 → 空")
    void noHitReturnsEmpty() {
        KeywordAutoReply r = new KeywordAutoReply();
        r.register("怎么收费", "回复");

        assertThat(r.replyFor("无关消息")).isEmpty();
    }

    @Test
    @DisplayName("空白关键词/回复 → 拒绝（空串会命中一切）")
    void blankRuleRejected() {
        KeywordAutoReply r = new KeywordAutoReply();

        assertThatThrownBy(() -> r.register("", "回复")).isInstanceOf(TggException.class);
        assertThatThrownBy(() -> r.register("词", null)).isInstanceOf(TggException.class);
    }

    @Test
    @DisplayName("null 文本 → 空（不抛）")
    void nullTextReturnsEmpty() {
        assertThat(new KeywordAutoReply().replyFor(null)).isEmpty();
    }
}
