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

/**
 * 交易群动作端口（公告/置顶/退群）。物理事实：Bot API 无 bot 建群/拉人方法
 * （telegrambots-meta 10.3.0 实测），交易群由用户创建、bot 被拉入后负责公告与归档退群。
 * 失败抛 {@link TradeGroupException}（不得静默），但交易主流程不因此回滚。
 */
public interface TradeGroupPort {

    /** 在群内发公告；返回消息 ID（供置顶）。 */
    int announce(long chatId, String text);

    /** 置顶群内消息。 */
    void pin(long chatId, int messageId);

    /** 退群（归档收尾）。 */
    void leave(long chatId);
}
