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
import com.tg.escrow.core.NoticePolicy;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.api.objects.webapp.WebAppInfo;
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

    /**
     * 带通知策略的文本回执：{@link NoticePolicy#silent()} → {@code disable_notification}。
     *
     * <p>刻意只映射 {@code silent} 这一个维度：{@code ephemeral}（Telegram 临时消息，库已支持
     * {@code ephemeralMessageParameters}）当前没有使用场景——本项目的通知事件全是 PERMANENT
     * 策略，映射它属于无人调用的代码。要用时再加，并同批补测试。
     */
    @Override
    public void sendText(long chatId, String text, NoticePolicy policy) {
        if (policy == null) {
            throw new TggException("回复出口：通知策略不可为空（chat=" + chatId + "）");
        }
        try {
            client.execute(SendMessage.builder()
                    .chatId(Long.toString(chatId))
                    .text(text)
                    .disableNotification(policy.silent())
                    .build());
        } catch (TelegramApiException ex) {
            throw new TggException("回复出口：发送文本失败（chat=" + chatId + "）", ex);
        }
    }

    @Override
    public void sendTextWithWebApp(long chatId, String text, String buttonText, String url) {
        if (url == null || url.isBlank()) {
            throw new TggException("回复出口：WebApp 按钮 URL 不可为空（chat=" + chatId + "）");
        }
        InlineKeyboardButton button = InlineKeyboardButton.builder()
                .text(buttonText)
                .webApp(WebAppInfo.builder().url(url).build())
                .build();
        InlineKeyboardMarkup markup = InlineKeyboardMarkup.builder()
                .keyboardRow(new InlineKeyboardRow(button))
                .build();
        try {
            client.execute(SendMessage.builder()
                    .chatId(Long.toString(chatId))
                    .text(text)
                    .replyMarkup(markup)
                    .build());
        } catch (TelegramApiException ex) {
            throw new TggException("回复出口：发送 WebApp 按钮消息失败（chat=" + chatId + "）", ex);
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
