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

import com.tg.escrow.core.ChannelMembershipPort.Membership;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatMember;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 频道订阅查询的 Telegram 适配器测试。
 *
 * <p>重点是<b>失败方向</b>：这里的错误映射会直接变成"踢人 / 不踢人"，比的不是文案而是人命。
 */
class TelegramChannelMembershipAdapterTest {

    @Test
    @DisplayName("status 映射表：creator/administrator/member=已订阅；left/kicked=未订阅；其余=查不清")
    void statusMappingTable() {
        assertThat(TelegramChannelMembershipAdapter.mapStatus("creator")).isEqualTo(Membership.SUBSCRIBED);
        assertThat(TelegramChannelMembershipAdapter.mapStatus("administrator")).isEqualTo(Membership.SUBSCRIBED);
        assertThat(TelegramChannelMembershipAdapter.mapStatus("member")).isEqualTo(Membership.SUBSCRIBED);
        assertThat(TelegramChannelMembershipAdapter.mapStatus(" LEFT ")).isEqualTo(Membership.NOT_SUBSCRIBED);
        assertThat(TelegramChannelMembershipAdapter.mapStatus("kicked")).isEqualTo(Membership.NOT_SUBSCRIBED);

        assertThat(TelegramChannelMembershipAdapter.mapStatus("restricted"))
                .as("restricted 在频道语境下语义含糊，含糊就不处置")
                .isEqualTo(Membership.UNKNOWN);
        assertThat(TelegramChannelMembershipAdapter.mapStatus("weird-new-status"))
                .isEqualTo(Membership.UNKNOWN);
        assertThat(TelegramChannelMembershipAdapter.mapStatus(null)).isEqualTo(Membership.UNKNOWN);
        assertThat(TelegramChannelMembershipAdapter.mapStatus("  ")).isEqualTo(Membership.UNKNOWN);
    }

    @Test
    @DisplayName("查询抛异常 → UNKNOWN（不是 NOT_SUBSCRIBED：一次网络抖动不得成为踢人理由）")
    void queryFailureIsUnknownNotUnsubscribed() throws TelegramApiException {
        TelegramClient client = mock(TelegramClient.class);
        doThrow(new TelegramApiException("boom")).when(client).execute(any(GetChatMember.class));

        Membership result = new TelegramChannelMembershipAdapter(client).membershipOf("@chan", 42L);

        assertThat(result)
                .as("故障必须落 UNKNOWN——落 NOT_SUBSCRIBED 会把已订阅的人误踢出群")
                .isEqualTo(Membership.UNKNOWN);
    }

    @Test
    @DisplayName("频道名为空/空白 → UNKNOWN，且一次请求都不发")
    void blankChannelDoesNotQuery() {
        TelegramClient client = mock(TelegramClient.class);
        TelegramChannelMembershipAdapter adapter = new TelegramChannelMembershipAdapter(client);

        assertThat(adapter.membershipOf(null, 42L)).isEqualTo(Membership.UNKNOWN);
        assertThat(adapter.membershipOf("   ", 42L)).isEqualTo(Membership.UNKNOWN);
        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("构造：client 为空 → 抛")
    void ctorRejectsNull() {
        assertThatThrownBy(() -> new TelegramChannelMembershipAdapter(null))
                .isInstanceOf(com.tg.escrow.common.TggException.class);
    }
}
