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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * BotReplyPort 的 Telegram 适配器（S1）的行为固定测试。
 *
 * <p>用 mock 的 {@link TelegramClient}——该接口有 20+ 个重载方法，手写 fake 不现实；
 * 本类要钉住的是「端口调用 → 正确的 Telegram 方法 + 正确字段」以及<b>失败不静默</b>。
 */
class TelegramBotReplyAdapterTest {

    @Test
    @DisplayName("sendText → execute(SendMessage)：chatId 十进制、text 原样")
    void sendTextExecutesSendMessage() throws TelegramApiException {
        TelegramClient client = mock(TelegramClient.class);
        TelegramBotReplyAdapter adapter = new TelegramBotReplyAdapter(client);

        adapter.sendText(123456L, "已创建订单 #1");

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(captor.capture());
        assertThat(captor.getValue().getChatId()).isEqualTo("123456");
        assertThat(captor.getValue().getText()).isEqualTo("已创建订单 #1");
    }

    @Test
    @DisplayName("ackCallback → execute(AnswerCallbackQuery)：id 原样（不应答会让按钮一直转圈）")
    void ackCallbackExecutesAnswerCallbackQuery() throws TelegramApiException {
        TelegramClient client = mock(TelegramClient.class);
        TelegramBotReplyAdapter adapter = new TelegramBotReplyAdapter(client);

        adapter.ackCallback("cb-42");

        ArgumentCaptor<AnswerCallbackQuery> captor =
                ArgumentCaptor.forClass(AnswerCallbackQuery.class);
        verify(client).execute(captor.capture());
        assertThat(captor.getValue().getCallbackQueryId()).isEqualTo("cb-42");
    }

    @Test
    @DisplayName("发送失败 → 包装为 TggException 上抛（不静默吞）")
    void sendFailureWrapped() throws TelegramApiException {
        TelegramClient client = mock(TelegramClient.class);
        doThrow(new TelegramApiException("boom")).when(client).execute(any(SendMessage.class));
        TelegramBotReplyAdapter adapter = new TelegramBotReplyAdapter(client);

        assertThatThrownBy(() -> adapter.sendText(1L, "x")).isInstanceOf(TggException.class);
    }

    @Test
    @DisplayName("应答失败 → 包装为 TggException 上抛（不掩盖）")
    void ackFailureWrapped() throws TelegramApiException {
        TelegramClient client = mock(TelegramClient.class);
        doThrow(new TelegramApiException("boom")).when(client).execute(any(AnswerCallbackQuery.class));
        TelegramBotReplyAdapter adapter = new TelegramBotReplyAdapter(client);

        assertThatThrownBy(() -> adapter.ackCallback("cb")).isInstanceOf(TggException.class);
    }

    @Test
    @DisplayName("构造：client 为空 → 抛")
    void ctorRejectsNull() {
        assertThatThrownBy(() -> new TelegramBotReplyAdapter(null))
                .isInstanceOf(TggException.class);
    }
}
