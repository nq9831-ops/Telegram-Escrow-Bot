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

import com.tg.escrow.core.MemberRole;
import com.tg.escrow.core.MemberRolePort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatMember;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMember;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link TelegramMemberRoleAdapter} 的行为固定测试（Wave 1）。
 *
 * <h2>它守的是什么</h2>
 * <p>角色是全部处置权限的<b>唯一依据</b>。本类把两件事钉死：① Telegram 的 status 到
 * {@link MemberRole} 的映射表；② <b>任何不确定情形一律退 MEMBER</b>——查询失败而给出
 * ADMIN/OWNER 等于把一次网络故障变成越权通道。
 *
 * <p>用 Mockito 伪造 Telegram 客户端：那是<b>第三方库边界</b>（无法起真 Telegram），
 * 与"mock 掉自家中间层"不是一回事——映射逻辑与 fail-closed 退化都在真实代码路径上跑。
 */
class TelegramMemberRoleAdapterTest {

    /** 造一个 Telegram 返回指定 status 的适配器。 */
    private static MemberRolePort portReturningStatus(String status) throws TelegramApiException {
        TelegramClient client = mock(TelegramClient.class);
        ChatMember member = mock(ChatMember.class);
        when(member.getStatus()).thenReturn(status);
        when(client.execute(any(GetChatMember.class))).thenReturn(member);
        return new TelegramMemberRoleAdapter(client);
    }

    @Test
    @DisplayName("creator → OWNER")
    void creatorIsOwner() throws Exception {
        assertThat(portReturningStatus("creator").roleOf(100L, 2002L)).isEqualTo(MemberRole.OWNER);
    }

    @Test
    @DisplayName("administrator → ADMIN")
    void administratorIsAdmin() throws Exception {
        assertThat(portReturningStatus("administrator").roleOf(100L, 2002L)).isEqualTo(MemberRole.ADMIN);
    }

    @Test
    @DisplayName("member / restricted / left / kicked → MEMBER（无管理权）")
    void ordinaryStatusesAreMember() throws Exception {
        for (String status : new String[]{"member", "restricted", "left", "kicked"}) {
            assertThat(portReturningStatus(status).roleOf(100L, 2002L))
                    .as("status=%s 不应获得任何管理权限", status)
                    .isEqualTo(MemberRole.MEMBER);
        }
    }

    @Test
    @DisplayName("未知 status 字符串 → MEMBER（不因没看懂而升权）")
    void unknownStatusIsMember() throws Exception {
        assertThat(portReturningStatus("some-future-status").roleOf(100L, 2002L))
                .isEqualTo(MemberRole.MEMBER);
    }

    @Test
    @DisplayName("status 为 null → MEMBER")
    void nullStatusIsMember() throws Exception {
        assertThat(portReturningStatus(null).roleOf(100L, 2002L)).isEqualTo(MemberRole.MEMBER);
    }

    @Test
    @DisplayName("【反证】查询抛异常 → MEMBER（fail-closed：故障不得变成越权）")
    void apiFailureDegradesToMember() throws Exception {
        TelegramClient client = mock(TelegramClient.class);
        when(client.execute(any(GetChatMember.class)))
                .thenThrow(new TelegramApiException("网络挂了"));

        assertThat(new TelegramMemberRoleAdapter(client).roleOf(100L, 2002L))
                .as("查询失败必须是 MEMBER，绝不能是 ADMIN/OWNER")
                .isEqualTo(MemberRole.MEMBER);
    }

    @Test
    @DisplayName("API 返回 null（无该成员）→ MEMBER")
    void nullMemberIsMember() throws Exception {
        TelegramClient client = mock(TelegramClient.class);
        when(client.execute(any(GetChatMember.class))).thenReturn(null);

        assertThat(new TelegramMemberRoleAdapter(client).roleOf(100L, 2002L)).isEqualTo(MemberRole.MEMBER);
    }

    @Test
    @DisplayName("构造：客户端缺失 → 抛（fail-fast，不静默裸奔）")
    void nullClientFailsFast() {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> new TelegramMemberRoleAdapter(null)))
                .isInstanceOf(com.tg.escrow.common.TggException.class);
    }
}
