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

/**
 * 回复出口（S1）：把回执交回 Telegram 的最小契约。
 *
 * <p>与 {@code TradeHistoryPort} 同一手法：TelegramBots 的 client API 在版本间变化大，
 * 抽出这个窄端口后，分发逻辑可纯本地穷举测试；上线时由 TelegramClient 适配器实现。
 */
public interface BotReplyPort {

    /** 发送文本回执。 */
    void sendText(long chatId, String text);

    /**
     * 应答 callback query（<b>必须调用</b>）。
     *
     * <p>不调用会让客户端按钮一直转圈直到超时——这是正确性问题而非体验优化，
     * 见 {@code TelegramBotHandler} 的 finally 不变量。
     */
    void ackCallback(String callbackQueryId);
}
