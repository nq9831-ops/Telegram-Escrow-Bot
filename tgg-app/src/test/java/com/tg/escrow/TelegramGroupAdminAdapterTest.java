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
import org.telegram.telegrambots.meta.api.methods.groupadministration.BanChatMember;
import org.telegram.telegrambots.meta.api.methods.groupadministration.RestrictChatMember;
import org.telegram.telegrambots.meta.api.methods.groupadministration.UnbanChatMember;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link TelegramGroupAdminAdapter} 的行为固定测试（Wave 2）。
 *
 * <h2>它守的是什么</h2>
 * <p>本类是「处置动作」真正落到 Telegram API 的<b>唯一落点</b>。测试把每个动作翻译成什么 API、
 * 带什么参数钉死——尤其两处易错：① 踢人需要 <b>Ban + Unban 两步</b>（Telegram 没有"踢"这个原子操作）；
 * ② 定时禁言要把时长换算成 <b>untilDate（unix 秒）</b>，算错会让禁言永不过期或立刻到期。
 *
 * <p>用 Mockito 伪造 Telegram 客户端：那是<b>第三方库边界</b>（起不了真 Telegram），
 * 与"mock 掉自家中间层"不是一回事。
 */
class TelegramGroupAdminAdapterTest {

    /** 真实群 ID 是负数——适配器必须原样传递，不能因符号出问题。 */
    private static final long CHAT = -1001234567890L;
    private static final long USER = 2002L;
    private static final Instant NOW = Instant.parse("2026-09-24T10:00:00Z");
    private static final Clock FIXED = Clock.fixed(NOW, ZoneOffset.UTC);

    private static TelegramGroupAdminAdapter adapter(TelegramClient client) {
        return new TelegramGroupAdminAdapter(client, FIXED);
    }

    @Test
    @DisplayName("kick = BanChatMember + UnbanChatMember（Telegram 无原子「踢」，须封后即解）")
    void kickBansThenUnbans() throws Exception {
        TelegramClient client = mock(TelegramClient.class);
        ArgumentCaptor<BanChatMember> ban = ArgumentCaptor.forClass(BanChatMember.class);
        ArgumentCaptor<UnbanChatMember> unban = ArgumentCaptor.forClass(UnbanChatMember.class);

        adapter(client).kick(CHAT, USER);

        verify(client).execute(ban.capture());
        verify(client).execute(unban.capture());
        assertThat(ban.getValue().getChatId()).isEqualTo(Long.toString(CHAT));
        assertThat(ban.getValue().getUserId()).isEqualTo(USER);
        assertThat(unban.getValue().getChatId()).isEqualTo(Long.toString(CHAT));
        assertThat(unban.getValue().getUserId()).isEqualTo(USER);
    }

    @Test
    @DisplayName("ban = 只 BanChatMember（不 Unban，否则等于没封）")
    void banOnlyBans() throws Exception {
        TelegramClient client = mock(TelegramClient.class);
        ArgumentCaptor<BanChatMember> ban = ArgumentCaptor.forClass(BanChatMember.class);

        adapter(client).ban(CHAT, USER);

        verify(client).execute(ban.capture());
        assertThat(ban.getValue().getChatId()).isEqualTo(Long.toString(CHAT));
        assertThat(ban.getValue().getUserId()).isEqualTo(USER);
    }

    @Test
    @DisplayName("mute = RestrictChatMember，禁发消息 + untilDate 精确等于 此刻+时长")
    void muteRestrictsWithUntilDate() throws Exception {
        TelegramClient client = mock(TelegramClient.class);
        ArgumentCaptor<RestrictChatMember> captor = ArgumentCaptor.forClass(RestrictChatMember.class);

        adapter(client).mute(CHAT, USER, Duration.ofHours(2));

        verify(client).execute(captor.capture());
        RestrictChatMember sent = captor.getValue();
        assertThat(sent.getChatId()).isEqualTo(Long.toString(CHAT));
        assertThat(sent.getUserId()).isEqualTo(USER);
        assertThat(sent.getPermissions().getCanSendMessages())
                .as("禁言必须真的禁掉发言权限")
                .isFalse();
        assertThat(sent.getUntilDate())
                .as("untilDate 必须是 unix 秒，且精确等于 注入时钟 + 时长")
                .isEqualTo((int) NOW.plus(Duration.ofHours(2)).getEpochSecond());
    }

    @Test
    @DisplayName("deleteMessage = DeleteMessage（消息 ID 原样传递）")
    void deleteSendsDeleteMessage() throws Exception {
        TelegramClient client = mock(TelegramClient.class);
        ArgumentCaptor<DeleteMessage> captor = ArgumentCaptor.forClass(DeleteMessage.class);

        adapter(client).deleteMessage(CHAT, 77L);

        verify(client).execute(captor.capture());
        assertThat(captor.getValue().getChatId()).isEqualTo(Long.toString(CHAT));
        assertThat(captor.getValue().getMessageId()).isEqualTo(77);
    }

    @Test
    @DisplayName("【反证】API 失败 → 抛 TggException，绝不静默（处置失败必须让调用方知道）")
    void apiFailureIsNotSilent() throws Exception {
        TelegramClient client = mock(TelegramClient.class);
        when(client.execute(any(BanChatMember.class))).thenThrow(new TelegramApiException("boom"));

        assertThatThrownBy(() -> adapter(client).kick(CHAT, USER))
                .as("静默吞掉处置失败，会让管理员以为人已被踢")
                .isInstanceOf(TggException.class);
    }

    @Test
    @DisplayName("构造：客户端或时钟缺失 → 抛（fail-fast）")
    void missingDepsFailFast() {
        assertThatThrownBy(() -> new TelegramGroupAdminAdapter(null, FIXED))
                .isInstanceOf(TggException.class);
        assertThatThrownBy(() -> new TelegramGroupAdminAdapter(mock(TelegramClient.class), null))
                .isInstanceOf(TggException.class);
    }
}
