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
import com.tg.escrow.core.ChannelMembershipPort;

import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatMember;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMember;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.Locale;

/**
 * {@link ChannelMembershipPort} 的 Telegram 实现——把「这人订没订这个频道」接到
 * Telegram {@code getChatMember} 上。
 *
 * <h2>映射表（与角色端口同族，但方向不同）</h2>
 * <p>{@code creator / administrator / member → SUBSCRIBED}；
 * {@code left / kicked → NOT_SUBSCRIBED}；<b>其余一律 {@link Membership#UNKNOWN}</b>。
 *
 * <p><b>关键差异</b>：角色端口对未知状态 fail-closed 退到最低权限（拒绝一次操作，用户重试即可）；
 * 这里若把未知/失败退成 {@code NOT_SUBSCRIBED}，后果是<b>把一个已订阅的人踢出群</b>——
 * 破坏性且不可撤销，比"少拦一个"严重得多。所以本实现的失败方向是
 * {@link Membership#UNKNOWN}（门卫据此既不踢也不放行）。
 *
 * <p>{@code restricted} 刻意归 UNKNOWN：它在频道语境下的可见性语义含糊，含糊就不处置。
 */
public final class TelegramChannelMembershipAdapter implements ChannelMembershipPort {

    private final TelegramClient client;

    /**
     * @param client Telegram 客户端（与角色查询/回复出口共用同一实例）
     */
    public TelegramChannelMembershipAdapter(TelegramClient client) {
        if (client == null) {
            throw new TggException("频道订阅查询：TelegramClient 不可为空");
        }
        this.client = client;
    }

    @Override
    public Membership membershipOf(String channel, long userId) {
        if (channel == null || channel.isBlank()) {
            return Membership.UNKNOWN;
        }
        try {
            ChatMember member = client.execute(new GetChatMember(channel, userId));
            if (member == null) {
                return Membership.UNKNOWN;
            }
            return mapStatus(member.getStatus());
        } catch (Exception ex) {
            // 故障 ≠ 未订阅：绝不能让一次网络抖动变成踢人理由
            return Membership.UNKNOWN;
        }
    }

    /**
     * Telegram status 字符串 → 订阅三态。未知/空白/含糊一律 {@link Membership#UNKNOWN}。
     *
     * <p>包级可见供单测直接打表。
     */
    static Membership mapStatus(String status) {
        if (status == null) {
            return Membership.UNKNOWN;
        }
        return switch (status.trim().toLowerCase(Locale.ROOT)) {
            case "creator", "administrator", "member" -> Membership.SUBSCRIBED;
            case "left", "kicked" -> Membership.NOT_SUBSCRIBED;
            default -> Membership.UNKNOWN;
        };
    }
}
