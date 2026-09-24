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
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/**
 * {@link BotReplyPort} 的 Telegram 实现（S1）——回执的唯一出口。
 *
 * <p>抽出窄端口的意义在此兑现：分发逻辑（{@code BotDispatcher}）与胶水层都不碰 Telegram
 * 类型，<b>本类是 Telegram 依赖的唯一落点</b>。换库或升级版本时只改这里。
 *
 * <h2>失败不静默</h2>
 * <p>发送/应答失败（网络、限流、被拉黑）一律包装为 {@link TggException} 上抛，不吞。
 * {@code ackCallback} 的失败同样上抛——「每个 callback 必应答」的不变量由调用方
 * （{@code TelegramBotHandler} 的 finally）负责保证，本层不替它掩盖失败。
 */
public final class TelegramBotReplyAdapter implements BotReplyPort {

    private final TelegramClient client;

    /**
     * @param client Telegram 客户端（生产为 {@code OkHttpTelegramClient}）
     */
    public TelegramBotReplyAdapter(TelegramClient client) {
        if (client == null) {
            throw new TggException("回复出口：TelegramClient 不可为空");
        }
        this.client = client;
    }

    @Override
    public void sendText(long chatId, String text) {
        try {
            client.execute(new SendMessage(Long.toString(chatId), text));
        } catch (TelegramApiException ex) {
            throw new TggException("回复出口：发送文本失败（chat=" + chatId + "）", ex);
        }
    }

    @Override
    public void ackCallback(String callbackQueryId) {
        try {
            client.execute(new AnswerCallbackQuery(callbackQueryId));
        } catch (TelegramApiException ex) {
            throw new TggException("回复出口：应答回调失败（id=" + callbackQueryId + "）", ex);
        }
    }
}
