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
import com.tg.escrow.core.GroupAdminPort;

import org.telegram.telegrambots.meta.api.methods.groupadministration.BanChatMember;
import org.telegram.telegrambots.meta.api.methods.groupadministration.RestrictChatMember;
import org.telegram.telegrambots.meta.api.methods.groupadministration.UnbanChatMember;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.objects.ChatPermissions;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.Clock;
import java.time.Duration;

/**
 * {@link GroupAdminPort} 的 Telegram 实现（Wave 2）——把编排层的处置决定真正落到 Bot API。
 *
 * <h2>两处易错，已在测试里钉死</h2>
 * <ol>
 *   <li><b>踢人不是原子操作</b>：Telegram 只有封禁。踢出语义 = {@code BanChatMember} 后立刻
 *       {@code UnbanChatMember}（封了再解，人离开群但后续可再加入）。只 ban 不 unban 就成了封禁，
 *       只 unban 不 ban 则什么也不发生。</li>
 *   <li><b>定时禁言要换算 untilDate（unix 秒）</b>：用注入的 {@link Clock} 计算，算错会让禁言
 *       永不过期、或写了等于没禁。禁言权限集把各类"发送"能力一并关掉——只关文本会留下发图/发文件的缺口。</li>
 * </ol>
 *
 * <h2>失败不静默</h2>
 * <p>任何 API 失败一律包装为 {@link TggException} 上抛。静默吞掉处置失败，会让管理员以为
 * 「人已经被踢了」——那比失败本身更危险。
 */
public final class TelegramGroupAdminAdapter implements GroupAdminPort {

    private final TelegramClient client;
    private final Clock clock;

    /**
     * @param client Telegram 客户端（生产为 {@code OkHttpTelegramClient}）
     * @param clock  时钟（禁言截止时刻据此计算，使测试可确定）
     */
    public TelegramGroupAdminAdapter(TelegramClient client, Clock clock) {
        if (client == null) {
            throw new TggException("群管理：TelegramClient 不可为空");
        }
        if (clock == null) {
            throw new TggException("群管理：时钟不可为空");
        }
        this.client = client;
        this.clock = clock;
    }

    @Override
    public void kick(long guildId, long userId) {
        String chatId = Long.toString(guildId);
        try {
            client.execute(new BanChatMember(chatId, userId));
            client.execute(new UnbanChatMember(chatId, userId));
        } catch (TelegramApiException ex) {
            throw new TggException("群管理：踢出失败（chat=" + guildId + ", user=" + userId + "）", ex);
        }
    }

    @Override
    public void ban(long guildId, long userId) {
        try {
            client.execute(new BanChatMember(Long.toString(guildId), userId));
        } catch (TelegramApiException ex) {
            throw new TggException("群管理：封禁失败（chat=" + guildId + ", user=" + userId + "）", ex);
        }
    }

    @Override
    public void mute(long guildId, long userId, Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new TggException("群管理：禁言时长必须为正，实为 " + duration);
        }
        int untilDate = (int) clock.instant().plus(duration).getEpochSecond();
        try {
            client.execute(new RestrictChatMember(
                    Long.toString(guildId), userId, deniedSendingPermissions(), untilDate, false));
        } catch (TelegramApiException ex) {
            throw new TggException("群管理：禁言失败（chat=" + guildId + ", user=" + userId + "）", ex);
        }
    }

    @Override
    public void deleteMessage(long guildId, long messageId) {
        try {
            client.execute(new DeleteMessage(Long.toString(guildId), (int) messageId));
        } catch (TelegramApiException ex) {
            throw new TggException("群管理：删消息失败（chat=" + guildId + ", message=" + messageId + "）", ex);
        }
    }

    /**
     * 禁言用的权限集：把各类「发送」能力全部关掉。
     *
     * <p>只关 {@code canSendMessages} 会留下发图/发文件/发投票的缺口——用户仍能刷屏，
     * 禁言就等于没禁。
     */
    private static ChatPermissions deniedSendingPermissions() {
        return ChatPermissions.builder()
                .canSendMessages(false)
                .canSendAudios(false)
                .canSendDocuments(false)
                .canSendPhotos(false)
                .canSendVideos(false)
                .canSendVideoNotes(false)
                .canSendVoiceNotes(false)
                .canSendPolls(false)
                .build();
    }
}
