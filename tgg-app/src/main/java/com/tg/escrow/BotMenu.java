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

import com.tg.escrow.common.TggException;

import java.util.ArrayList;
import java.util.List;

/**
 * Telegram 命令菜单（输入框敲 {@code /} 时弹出的列表）的内容——<b>纯数据、可测</b>，
 * 不含 Telegram 依赖（转成 {@code telegrambots} 类型的职责在 {@link BotMenuRegistrar}）。
 *
 * <h2>为什么需要它</h2>
 * <p>此前全仓没有 {@code setMyCommands}——用户在输入框敲 {@code /} <b>看不到任何命令</b>，
 * 只能靠记住用法说明或翻回执。菜单是 bot 的第一可发现性入口：把命令登记到
 * default scope，点一下就能用，不必记语法。
 *
 * <h2>与真实命令的一致性</h2>
 * <p>用户命令对应 {@code /escrow}、{@code /help}、{@code /start} 的既有行为。
 * 改命令名时两处必须同步——{@code BotMenuTest} 会把漂移钉成红色。
 */
public final class BotMenu {

    private BotMenu() {
    }

    /** 用户命令（default scope）——普通成员/私聊里看到的三条。 */
    public static List<Command> userCommands() {
        return List.of(
                new Command("escrow", "担保交易：发起 / 推进 / 查询 / 信用榜单"),
                new Command("help", "帮助：全部命令与用法"),
                new Command("start", "开始使用（打开交易表单）"));
    }

    /**
     * 一个菜单项。
     *
     * <p>Telegram 的硬约束（command 1–32 字符、仅小写字母/数字/下划线；description 1–256 字符）
     * 在构造期拦下——非法的东西不该活到 API 调用那一刻才炸（且那时的报错不含"是谁在构造什么"）。
     *
     * @param name        命令名（不含前导 {@code /}）
     * @param description 菜单上显示的一行说明
     */
    public record Command(String name, String description) {

        /** 与 {@code CommandParser.MAX_COMMAND_NAME_LENGTH} 同源（Telegram 的命令名上限）。 */
        private static final int MAX_NAME = 32;

        /** Telegram 对描述文本的字符上限。 */
        private static final int MAX_DESCRIPTION = 256;

        public Command {
            if (name == null || name.isBlank() || name.length() > MAX_NAME
                    || !name.matches("[a-z0-9_]+")) {
                throw new TggException("菜单命令：命令名须为 1–" + MAX_NAME
                        + " 个字符，仅小写字母/数字/下划线（实为「" + name + "」）");
            }
            if (description == null || description.isBlank()) {
                throw new TggException("菜单命令：描述不可为空（空描述在菜单里是一行空白）");
            }
            if (description.length() > MAX_DESCRIPTION) {
                throw new TggException("菜单命令：描述不可超过 " + MAX_DESCRIPTION
                        + " 字符（实为 " + description.length() + "）");
            }
        }
    }
}
