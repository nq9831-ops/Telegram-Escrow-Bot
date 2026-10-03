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

import com.tg.escrow.core.TradeGroupException;
import com.tg.escrow.core.TradeGroupPort;
import org.telegram.telegrambots.meta.api.methods.groupadministration.LeaveChat;
import org.telegram.telegrambots.meta.api.methods.pinnedmessages.PinChatMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/**
 * {@link TradeGroupPort} 的 Telegram 实现——公告/置顶/退群三动作。
 *
 * <p>能力边界（物理事实，非取舍）：Bot API 没有 bot 建群与拉人方法，
 * 交易群由用户创建并把 bot 拉进群；本适配器只做 bot 入群后的群面动作。
 *
 * <p>失败一律转 {@link TradeGroupException}（透传原因）——<b>群侧失败不回滚交易</b>，
 * 但调用方必须知道失败（回执如实说「群动作未生效」，绝不假装已发公告）。
 */
public final class TelegramTradeGroupAdapter implements TradeGroupPort {

    private final TelegramClient client;

    public TelegramTradeGroupAdapter(TelegramClient client) {
        if (client == null) {
            throw new TradeGroupException("交易群：TelegramClient 不可为空");
        }
        this.client = client;
    }

    @Override
    public int announce(long chatId, String text) {
        try {
            Message sent = client.execute(new SendMessage(Long.toString(chatId), text));
            return sent.getMessageId();
        } catch (TelegramApiException ex) {
            throw new TradeGroupException("交易群：公告发送失败（chat=" + chatId + "）", ex);
        }
    }

    @Override
    public void pin(long chatId, int messageId) {
        try {
            client.execute(new PinChatMessage(Long.toString(chatId), messageId));
        } catch (TelegramApiException ex) {
            throw new TradeGroupException("交易群：公告置顶失败（chat=" + chatId + ", msg=" + messageId + "）", ex);
        }
    }

    @Override
    public void leave(long chatId) {
        try {
            client.execute(new LeaveChat(Long.toString(chatId)));
        } catch (TelegramApiException ex) {
            throw new TradeGroupException("交易群：退群失败（chat=" + chatId + "）", ex);
        }
    }
}
