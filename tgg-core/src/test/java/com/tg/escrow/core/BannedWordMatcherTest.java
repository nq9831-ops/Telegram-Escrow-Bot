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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 违禁词匹配的行为固定测试。
 *
 * <p>群管理里这类"判定"代码的危险不在漏判，而在<b>误判</b>：一次误判就是一条被
 * 错误删除的正常消息。所以边界（空词表、空文本、空白词条、大小写）必须逐个钉死，
 * 而不是靠"看起来能用"。
 */
class BannedWordMatcherTest {

    @Test
    @DisplayName("精确词以子串方式命中——不要求词边界")
    void exactWordMatchesAsSubstring() {
        BannedWordMatcher matcher = BannedWordMatcher.compile(List.of("bc"), List.of());

        assertThat(matcher.matches("xbcx")).isTrue();
        assertThat(matcher.firstMatch("xbcx"))
                .hasValueSatisfying(m -> {
                    assertThat(m.rule()).isEqualTo("bc");
                    assertThat(m.kind()).isEqualTo(BannedWordMatcher.Kind.EXACT);
                });
    }

    @Test
    @DisplayName("大小写不同仍命中——大小写变换是最常见的规避手段")
    void exactWordIsCaseInsensitive() {
        BannedWordMatcher matcher = BannedWordMatcher.compile(List.of("Spam"), List.of());

        assertThat(matcher.matches("this is SPAM")).isTrue();
        assertThat(matcher.matches("this is spam")).isTrue();
        assertThat(matcher.matches("this is sPaM")).isTrue();
    }

    @Test
    @DisplayName("中文词命中")
    void chineseWordMatches() {
        BannedWordMatcher matcher = BannedWordMatcher.compile(List.of("加微信"), List.of());

        assertThat(matcher.matches("想了解更多加微信聊")).isTrue();
        assertThat(matcher.firstMatch("想了解更多加微信聊"))
                .hasValueSatisfying(m -> assertThat(m.rule()).isEqualTo("加微信"));
    }

    @Test
    @DisplayName("正则模式命中并被标记为 REGEX")
    void regexPatternMatches() {
        BannedWordMatcher matcher = BannedWordMatcher.compile(List.of(), List.of("\\d{11}"));

        assertThat(matcher.matches("我的手机 13800138000 请打")).isTrue();
        assertThat(matcher.firstMatch("我的手机 13800138000 请打"))
                .hasValueSatisfying(m -> assertThat(m.kind()).isEqualTo(BannedWordMatcher.Kind.REGEX));
    }

    @Test
    @DisplayName("正则不匹配时不算命中")
    void regexNonMatchIsNotHit() {
        BannedWordMatcher matcher = BannedWordMatcher.compile(List.of(), List.of("\\d{11}"));

        assertThat(matcher.matches("只有 3 位 123")).isFalse();
    }

    @Test
    @DisplayName("精确词与正则同时存在时，精确词优先返回（顺序稳定，结果可复现）")
    void exactTakesPrecedenceOverRegex() {
        BannedWordMatcher matcher = BannedWordMatcher.compile(List.of("bc"), List.of("b."));

        assertThat(matcher.firstMatch("abc"))
                .hasValueSatisfying(m -> assertThat(m.kind()).isEqualTo(BannedWordMatcher.Kind.EXACT));
    }

    @Test
    @DisplayName("无命中返回 empty，不抛异常——无命中是绝大多数消息的正常结局")
    void noMatchReturnsEmpty() {
        BannedWordMatcher matcher = BannedWordMatcher.compile(List.of("bc"), List.of("\\d{11}"));

        assertThat(matcher.matches("完全正常的消息")).isFalse();
        assertThat(matcher.firstMatch("完全正常的消息")).isEmpty();
    }

    @Test
    @DisplayName("空词表 → 永不命中（不是命中一切）")
    void emptyWordListNeverMatches() {
        BannedWordMatcher matcher = BannedWordMatcher.compile(List.of(), List.of());

        assertThat(matcher.matches("任何消息")).isFalse();
        assertThat(matcher.firstMatch("任何消息")).isEmpty();
    }

    @Test
    @DisplayName("空白词条被忽略——否则空串会匹配一切文本")
    void blankEntriesAreIgnored() {
        BannedWordMatcher matcher = BannedWordMatcher.compile(List.of("", "   ", "\t"), List.of());

        assertThat(matcher.matches("任何消息")).isFalse();
        assertThat(matcher.firstMatch("任何消息")).isEmpty();
    }

    @Test
    @DisplayName("空白正则也被忽略（空白正则不产生匹配，但不该被编译进去）")
    void blankRegexIsIgnored() {
        BannedWordMatcher matcher = BannedWordMatcher.compile(List.of(), List.of("", "  "));

        assertThat(matcher.matches("任何消息")).isFalse();
    }

    @Test
    @DisplayName("空文本 / null 文本 → 不命中，且不抛异常（非文本消息是常态）")
    void blankOrNullTextNeverMatches() {
        BannedWordMatcher matcher = BannedWordMatcher.compile(List.of("bc"), List.of("b."));

        assertThat(matcher.matches("")).isFalse();
        assertThat(matcher.matches(null)).isFalse();
        assertThat(matcher.firstMatch(null)).isEmpty();
    }

    @Test
    @DisplayName("非法正则 → 构造期抛 TggException（fail-fast，不延迟到首次匹配）")
    void invalidRegexFailsAtCompileTime() {
        assertThatThrownBy(() -> BannedWordMatcher.compile(List.of(), List.of("[未闭合")))
                .isInstanceOf(TggException.class)
                .hasMessageContaining("[未闭合");
    }

    @Test
    @DisplayName("超长正则被拒——灾难性回溯（ReDoS）的入口是没人管的正则长度")
    void overlongRegexIsRejected() {
        String huge = "a".repeat(BannedWordMatcher.MAX_REGEX_LENGTH + 1);

        assertThatThrownBy(() -> BannedWordMatcher.compile(List.of(), List.of(huge)))
                .isInstanceOf(TggException.class);
    }

    @Test
    @DisplayName("null 词表/正则表视为空（配置缺失不崩，但也不意外命中）")
    void nullListsTreatedAsEmpty() {
        BannedWordMatcher matcher = BannedWordMatcher.compile(null, null);

        assertThat(matcher.matches("任何消息")).isFalse();
    }

    @Test
    @DisplayName("多个精确词时，命中其一即返回（返回的规则是实际命中的那个）")
    void returnsTheActuallyMatchedRule() {
        BannedWordMatcher matcher = BannedWordMatcher.compile(List.of("甲词", "乙词"), List.of());

        assertThat(matcher.firstMatch("这里只有乙词"))
                .hasValueSatisfying(m -> assertThat(m.rule()).isEqualTo("乙词"));
    }
}
