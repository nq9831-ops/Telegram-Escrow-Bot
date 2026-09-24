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

/**
 * 新成员入群事件（Wave 2）——欢迎与入群风控的输入。
 *
 * <p>此前入站门禁只看文本/媒体，Telegram 的 {@code new_chat_members} 根本进不来，
 * 于是 {@link WelcomeTemplate} 与 {@link ProtectionMode} 这两个已实现、有测试的类
 * <b>永远没有机会上场</b>。本载体是让它们能真正生效的前提。
 *
 * @param chatId    群 ID
 * @param chatTitle 群名称（欢迎语里的 {@code {group}}；可能为空）
 * @param userId    新成员 ID
 * @param userName  新成员显示名（欢迎语里的 {@code {username}}；可能为空）
 */
public record MemberJoined(long chatId, String chatTitle, long userId, String userName) {

    public MemberJoined {
        if (chatId == 0) {
            throw new TggException("入群事件：群 ID 未提供");
        }
        if (userId == 0) {
            throw new TggException("入群事件：新成员 ID 未提供");
        }
    }
}
