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

import com.tg.escrow.core.NoticePolicy;

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
     * 发送文本回执，并声明这条消息的通知策略（GM-36）。
     *
     * <p>与 {@link #sendText(long, String)} 的唯一区别：把 {@link NoticePolicy#silent()}
     * 落到 Telegram 的 {@code disable_notification}。<b>它只影响响铃、不影响送达</b>——
     * 静默不等于消息丢失；这也正是"静默时段"敢压制非关键通知的前提。
     *
     * @param policy 通知策略（不可为空——不静默降级到某个默认策略）
     */
    void sendText(long chatId, String text, NoticePolicy policy);

    /**
     * 发送带「打开表单」按钮的文本回执（S6 / Wave 3）。
     *
     * <p>按钮为 Telegram inline {@code web_app}——点击在客户端内打开 Mini App 页面。
     * {@code url} 必须是 <b>HTTPS</b>（Telegram 硬性要求，且需与 bot 关联的域名一致）。
     *
     * @param buttonText 按钮文字
     * @param url        表单页地址（HTTPS）
     */
    void sendTextWithWebApp(long chatId, String text, String buttonText, String url);

    /**
     * 应答 callback query（<b>必须调用</b>）。
     *
     * <p>不调用会让客户端按钮一直转圈直到超时——这是正确性问题而非体验优化，
     * 见 {@code TelegramBotHandler} 的 finally 不变量。
     */
    void ackCallback(String callbackQueryId);
}
