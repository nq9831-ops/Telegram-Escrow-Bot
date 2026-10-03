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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.groupadministration.LeaveChat;
import org.telegram.telegrambots.meta.api.methods.pinnedmessages.PinChatMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link TelegramTradeGroupAdapter} 的契约测试——钉住两件事：
 * <ol>
 *   <li><b>能力边界</b>：只封装公告/置顶/退群（Bot API 无 bot 建群/拉人方法，
 *       交易群由用户创建、bot 被拉入——见 {@code TradeGroupPort} javadoc 的偏差记录）；</li>
 *   <li><b>失败如实</b>：Telegram 调用失败转 {@link TradeGroupException} 且透传原因，
 *       <b>不静默吞掉</b>——回执要如实说「群动作未生效」，绝不假装已发公告。</li>
 * </ol>
 */
class TelegramTradeGroupAdapterTest {

    private static final long CHAT_ID = -1001234567890L;

    @Test
    @DisplayName("announce 成功 → 返回公告消息 ID（供置顶）")
    void announceReturnsMessageId() throws Exception {
        TelegramClient client = mock(TelegramClient.class);
        Message sent = mock(Message.class);
        when(sent.getMessageId()).thenReturn(42);
        when(client.execute(any(SendMessage.class))).thenReturn(sent);

        int messageId = new TelegramTradeGroupAdapter(client).announce(CHAT_ID, "公告");

        assertThat(messageId).isEqualTo(42);
    }

    @Test
    @DisplayName("pin / leave 成功 → 正常返回")
    void pinAndLeaveSucceed() throws Exception {
        TelegramClient client = mock(TelegramClient.class);
        when(client.execute(any(PinChatMessage.class))).thenReturn(true);
        when(client.execute(any(LeaveChat.class))).thenReturn(true);

        TelegramTradeGroupAdapter adapter = new TelegramTradeGroupAdapter(client);
        adapter.pin(CHAT_ID, 42);
        adapter.leave(CHAT_ID);
    }

    @Test
    @DisplayName("Telegram 调用失败 → TradeGroupException 且透传原因（不静默）")
    void failuresAreWrappedWithCause() throws Exception {
        TelegramClient client = mock(TelegramClient.class);
        TelegramApiException boom = new TelegramApiException("bot was kicked");
        when(client.execute(any(SendMessage.class))).thenThrow(boom);
        when(client.execute(any(PinChatMessage.class))).thenThrow(boom);
        doThrow(boom).when(client).execute(any(LeaveChat.class));

        TelegramTradeGroupAdapter adapter = new TelegramTradeGroupAdapter(client);

        assertThatThrownBy(() -> adapter.announce(CHAT_ID, "公告"))
                .isInstanceOf(TradeGroupException.class)
                .hasMessageContaining("公告")
                .hasCause(boom);
        assertThatThrownBy(() -> adapter.pin(CHAT_ID, 42))
                .isInstanceOf(TradeGroupException.class)
                .hasCause(boom);
        assertThatThrownBy(() -> adapter.leave(CHAT_ID))
                .isInstanceOf(TradeGroupException.class)
                .hasCause(boom);
    }

    @Test
    @DisplayName("TelegramClient 未提供 → 构造即抛（fail-fast）")
    void nullClientFailsFast() {
        assertThatThrownBy(() -> new TelegramTradeGroupAdapter(null))
                .isInstanceOf(TradeGroupException.class);
    }
}
