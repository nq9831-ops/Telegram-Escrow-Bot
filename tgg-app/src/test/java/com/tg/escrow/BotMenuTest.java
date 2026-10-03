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
package com.tg.escrow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 命令菜单内容的行为固定测试。
 *
 * <p>菜单是用户对 bot 的<b>第一印象</b>（在输入框敲 {@code /} 时弹出）——命令名/描述
 * 一旦与真实命令漂移，用户会被指向不存在的入口。故逐项钉住：
 * <ol>
 *   <li>命令集合与真实命令一致（{@code /escrow}、{@code /help}、{@code /start}）；</li>
 *   <li>Telegram 的硬约束（命令名 1–32 字符 [a-z0-9_]、描述 1–256 字符）在构造期拦下——</li>
 *   <li>非法名/空描述不允许流到 API 才炸。</li>
 * </ol>
 */
class BotMenuTest {

    @Test
    @DisplayName("用户命令：escrow / help / start，描述全部非空")
    void userCommandsCoverCoreActions() {
        var cmds = BotMenu.userCommands();
        assertThat(cmds).extracting(BotMenu.Command::name)
                .containsExactly("escrow", "help", "start");
        assertThat(cmds).allSatisfy(c -> assertThat(c.description()).isNotBlank());
    }

    @Test
    @DisplayName("命令名违反 Telegram 约束（大小写/符号/超长/空）——构造期即抛，不放行到 API")
    void commandNameValidatedAtConstruction() {
        assertThatThrownBy(() -> new BotMenu.Command("Escrow", "x"))
                .isInstanceOf(com.tg.escrow.common.TggException.class)
                .hasMessageContaining("命令名");
        assertThatThrownBy(() -> new BotMenu.Command("a b", "x"))
                .hasMessageContaining("命令名");
        assertThatThrownBy(() -> new BotMenu.Command("", "x"))
                .hasMessageContaining("命令名");
        assertThatThrownBy(() -> new BotMenu.Command("a".repeat(33), "x"))
                .hasMessageContaining("命令名");
    }

    @Test
    @DisplayName("描述不可为空、不可超 256 字符——空描述在菜单里是一行空白")
    void descriptionValidatedAtConstruction() {
        assertThatThrownBy(() -> new BotMenu.Command("ok", " "))
                .isInstanceOf(com.tg.escrow.common.TggException.class)
                .hasMessageContaining("描述");
        assertThatThrownBy(() -> new BotMenu.Command("ok", "x".repeat(257)))
                .hasMessageContaining("描述");
    }
}
