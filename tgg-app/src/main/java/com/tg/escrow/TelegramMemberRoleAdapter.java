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
import com.tg.escrow.core.MemberRole;
import com.tg.escrow.core.MemberRolePort;

import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatMember;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMember;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/**
 * {@link MemberRolePort} 的 Telegram 实现（Wave 1）——把「群里这个人是什么角色」接到
 * Telegram {@code getChatMember} 上。
 *
 * <h2>为什么必须有它</h2>
 * <p>此前 {@code TelegramBotHandler} 把角色<b>硬编码</b>为 {@link MemberRole#MEMBER}，
 * 于是 {@code ModerationOrchestrator} 的角色守卫会把所有管理员一律拒掉——处置功能接了线也点不动。
 * 本类是该阻塞的解法。
 *
 * <h2>fail-closed</h2>
 * <p>查询失败（网络/权限/被封）、返回 {@code null}、或 status 是没见过的字符串，一律退
 * {@link MemberRole#MEMBER}。角色越权比"拒绝一次管理员操作"危险得多：后者用户重试即可，
 * 前者是让普通成员拿到处置权。
 *
 * <p>映射表（Telegram status → 本项目角色）：{@code creator → OWNER}、
 * {@code administrator → ADMIN}、其余（member/restricted/left/kicked/未知）{@code → MEMBER}。
 */
public final class TelegramMemberRoleAdapter implements MemberRolePort {

    private final TelegramClient client;

    /**
     * @param client Telegram 客户端（生产为 {@code OkHttpTelegramClient}）
     */
    public TelegramMemberRoleAdapter(TelegramClient client) {
        if (client == null) {
            throw new TggException("角色查询：TelegramClient 不可为空");
        }
        this.client = client;
    }

    @Override
    public MemberRole roleOf(long chatId, long userId) {
        try {
            ChatMember member = client.execute(new GetChatMember(Long.toString(chatId), userId));
            if (member == null) {
                return MemberRole.MEMBER;
            }
            return mapStatus(member.getStatus());
        } catch (Exception ex) {
            // fail-closed：查询失败绝不得变成权限提升
            return MemberRole.MEMBER;
        }
    }

    /**
     * Telegram status 字符串 → 本项目角色。未知/空白一律 {@link MemberRole#MEMBER}。
     *
     * <p>包级可见供单测直接打表。
     */
    static MemberRole mapStatus(String status) {
        if (status == null) {
            return MemberRole.MEMBER;
        }
        return switch (status.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "creator" -> MemberRole.OWNER;
            case "administrator" -> MemberRole.ADMIN;
            default -> MemberRole.MEMBER;
        };
    }
}
