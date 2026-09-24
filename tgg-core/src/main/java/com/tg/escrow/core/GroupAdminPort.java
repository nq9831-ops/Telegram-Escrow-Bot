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

import java.time.Duration;

/**
 * 群管理动作端口（GM-01/02/08）——踢/封/禁言/删消息的最小契约。
 *
 * <p>与 {@code TradeHistoryPort}/{@code BotReplyPort} 同一手法：真实实现在装配层对接
 * Telegram Bot API（属线上清单 A 组），本端口让处置编排（{@link ModerationOrchestrator}）
 * 可纯本地穷举测试。失败抛异常（不得静默——处置失败必须让调用方知道）。
 */
public interface GroupAdminPort {

    /** 踢出成员。 */
    void kick(long guildId, long userId);

    /** 封禁成员。 */
    void ban(long guildId, long userId);

    /** 禁言（定时，到期自动解除——解除动作由调度侧触发）。 */
    void mute(long guildId, long userId, Duration duration);

    /** 删除消息（GM-08 违规消息处置）。 */
    void deleteMessage(long guildId, long messageId);
}
