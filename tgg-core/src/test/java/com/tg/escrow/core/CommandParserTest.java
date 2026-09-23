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
 * 命令解析的行为固定测试。
 *
 * <p>解析器是 Bot 的第一道面：它的宽容度直接决定用户体验，它的严格度决定安全边界。
 * 所以这里逐条钉住三件事——什么算命令、{@code @botname} 后缀怎么处理、
 * 哪些输入必须被拒绝。
 */
class CommandParserTest {

    @Test
    @DisplayName("无参数命令")
    void bareCommand() {
        assertThat(CommandParser.parse("/start"))
                .hasValueSatisfying(cmd -> {
                    assertThat(cmd.name()).isEqualTo("start");
                    assertThat(cmd.args()).isEmpty();
                });
    }

    @Test
    @DisplayName("带参数命令按空白切分")
    void commandWithArgs() {
        assertThat(CommandParser.parse("/ban 123 广告刷屏"))
                .hasValueSatisfying(cmd -> {
                    assertThat(cmd.name()).isEqualTo("ban");
                    assertThat(cmd.args()).containsExactly("123", "广告刷屏");
                });
    }

    @Test
    @DisplayName("参数内含空格时不做引号解析——Telegram 不是 shell，引号是普通字符")
    void quotesAreLiteral() {
        assertThat(CommandParser.parse("/ban 123 \"广告 刷屏\""))
                .hasValueSatisfying(cmd -> assertThat(cmd.args())
                        .containsExactly("123", "\"广告", "刷屏\""));
    }

    @Test
    @DisplayName("多个连续空白不产生空参数")
    void repeatedWhitespaceCollapses() {
        assertThat(CommandParser.parse("/ban    123   广告"))
                .hasValueSatisfying(cmd -> assertThat(cmd.args())
                        .containsExactly("123", "广告"));
    }

    @Test
    @DisplayName("命令名归一化为小写——Telegram 客户端可能大小写不一")
    void commandNameIsLowercased() {
        assertThat(CommandParser.parse("/START")).hasValueSatisfying(
                cmd -> assertThat(cmd.name()).isEqualTo("start"));
    }

    @Test
    @DisplayName("参数保持原样，不被小写化（用户 ID、理由都不该被改）")
    void argsKeepOriginalCase() {
        assertThat(CommandParser.parse("/ban ABC DEF"))
                .hasValueSatisfying(cmd -> assertThat(cmd.args()).containsExactly("ABC", "DEF"));
    }

    @Test
    @DisplayName("@本机器人 后缀被剥离")
    void ownBotSuffixIsStripped() {
        assertThat(CommandParser.parse("/ban@mybot 123", "mybot"))
                .hasValueSatisfying(cmd -> {
                    assertThat(cmd.name()).isEqualTo("ban");
                    assertThat(cmd.args()).containsExactly("123");
                });
    }

    @Test
    @DisplayName("@本机器人 后缀大小写不敏感")
    void ownBotSuffixIsCaseInsensitive() {
        assertThat(CommandParser.parse("/ban@MyBot 123", "mybot"))
                .hasValueSatisfying(cmd -> assertThat(cmd.name()).isEqualTo("ban"));
        assertThat(CommandParser.parse("/ban@mybot 123", "MYBOT"))
                .hasValueSatisfying(cmd -> assertThat(cmd.name()).isEqualTo("ban"));
    }

    @Test
    @DisplayName("发给其他机器人的命令 → 不响应（群里可能有多个 bot）")
    void otherBotSuffixIsIgnored() {
        assertThat(CommandParser.parse("/ban@otherbot 123", "mybot")).isEmpty();
    }

    @Test
    @DisplayName("带 @ 后缀但未指定本机器人名 → 不响应（无从判断是否该我处理）")
    void suffixedCommandWithoutExpectedBotIsIgnored() {
        assertThat(CommandParser.parse("/ban@mybot 123")).isEmpty();
    }

    @Test
    @DisplayName("非命令文本 → 不解析（普通聊天不该触发命令）")
    void plainTextIsNotACommand() {
        assertThat(CommandParser.parse("你好")).isEmpty();
        assertThat(CommandParser.parse("看 /start 这个命令")).isEmpty();
    }

    @Test
    @DisplayName("null / 空 / 纯空白 → 不解析，不抛异常（消息可能没有文本）")
    void nullAndBlankAreSafe() {
        assertThat(CommandParser.parse(null)).isEmpty();
        assertThat(CommandParser.parse("")).isEmpty();
        assertThat(CommandParser.parse("   ")).isEmpty();
    }

    @Test
    @DisplayName("仅斜杠、或以斜杠开头但无命令名 → 不解析")
    void slashWithoutNameIsNotACommand() {
        assertThat(CommandParser.parse("/")).isEmpty();
        assertThat(CommandParser.parse("/ 123")).isEmpty();
    }

    @Test
    @DisplayName("命令名超长 → 不解析（Telegram 命令名上限 32 字符，超长只可能不是命令）")
    void overlongCommandNameIsRejected() {
        String longName = "a".repeat(CommandParser.MAX_COMMAND_NAME_LENGTH + 1);
        assertThat(CommandParser.parse("/" + longName)).isEmpty();

        String okName = "a".repeat(CommandParser.MAX_COMMAND_NAME_LENGTH);
        assertThat(CommandParser.parse("/" + okName)).isPresent();
    }

    @Test
    @DisplayName("命令名含非法字符（空格、标点）→ 不解析")
    void invalidCommandNameCharsAreRejected() {
        assertThat(CommandParser.parse("/ba!n")).isEmpty();
        assertThat(CommandParser.parse("/ba,n")).isEmpty();
    }

    @Test
    @DisplayName("下划线是合法命令名（Telegram 允许）")
    void underscoreIsValidInName() {
        assertThat(CommandParser.parse("/my_cmd"))
                .hasValueSatisfying(cmd -> assertThat(cmd.name()).isEqualTo("my_cmd"));
    }

    @Test
    @DisplayName("前导空白被容忍（消息可能被转发或粘贴带上空白）")
    void leadingWhitespaceTolerated() {
        assertThat(CommandParser.parse("   /start"))
                .hasValueSatisfying(cmd -> assertThat(cmd.name()).isEqualTo("start"));
    }

    @Test
    @DisplayName("argOpt 越界返回 empty，不抛异常（可选参数是常态）")
    void argOptIsBoundsSafe() {
        CommandParser.parse("/ban 123").ifPresent(cmd -> {
            assertThat(cmd.argOpt(0)).contains("123");
            assertThat(cmd.argOpt(1)).isEmpty();
            assertThat(cmd.argOpt(99)).isEmpty();
        });
    }

    @Test
    @DisplayName("无参数命令的参数列表不可变（防止调用方误改）")
    void argsAreImmutable() {
        CommandParser.parse("/start").ifPresent(cmd ->
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> cmd.args().add("x"))
                        .isInstanceOf(UnsupportedOperationException.class));
    }
}
