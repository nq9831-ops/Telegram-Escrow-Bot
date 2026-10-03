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
package com.tg.escrow.core;

import com.tg.escrow.common.TggException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 入站消息的行为固定测试（GM-12 接线）：<b>群内以频道身份发的消息必须能被承载</b>。
 *
 * <h2>为什么这条最要紧</h2>
 * <p>此前 {@code userId == 0} 被构造期一律拒绝，而 Telegram 侧"以频道身份发帖"的消息
 * {@code getFrom()} 为 {@code null} → 转换时拿到 0 → 构造即抛 → 那条消息
 * <b>整条不被处理</b>（内容安全与命令都不发生）。也就是说：不是"漏判了一条可疑频道帖"，
 * 而是"这类消息从未进入过判定"。
 *
 * <h2>不变量怎么改的（fail-closed 保持）</h2>
 * <p>原来的"发送者必须有 ID"是 fail-closed 的（不知道是谁就别处置）。新规则是它的
 * <b>精确化</b>而非放宽：<b>{@code userId == 0} 只有在显式标注"发送者是频道"时才允许</b>。
 * 既不是频道又报不出 userId 的消息仍然当场拒绝——没有任何输入会因为这条改动而
 * 从"拒绝"变成"不确定地放过"。
 */
class IncomingMessageTest {

    private static final long CHAT = -1001234567890L;
    private static final long USER = 2002L;
    private static final long MSG = 77L;

    @Test
    @DisplayName("频道帖：无个人发送者（userId=0）但标注了频道身份 → 可构造（这是本单元要修的核心缺口）")
    void channelPostIsCarriedInsteadOfRejected() {
        IncomingMessage post = IncomingMessage.channelPost(CHAT, MSG, "看这个",
                IncomingMessage.MediaKind.NONE, null, null, ChatKind.SUPERGROUP, "SomeChannel");

        assertThat(post.userId()).as("频道帖没有个人发送者").isZero();
        assertThat(post.senderIsChannel()).isTrue();
        assertThat(post.senderName()).isEqualTo("SomeChannel");
        assertThat(post.chatId()).isEqualTo(CHAT);
        assertThat(post.messageId()).isEqualTo(MSG);
    }

    @Test
    @DisplayName("频道帖没有可读频道名时同样可构造——由守卫按「无法识别即拦」处理，不由构造期判死")
    void channelPostWithoutNameIsStillCarried() {
        IncomingMessage post = IncomingMessage.channelPost(CHAT, MSG, null,
                IncomingMessage.MediaKind.NONE, null, null, ChatKind.SUPERGROUP, null);

        assertThat(post.senderIsChannel()).isTrue();
        assertThat(post.senderName()).isNull();
    }

    @Test
    @DisplayName("既不是频道、又报不出 userId → 仍然当场拒绝（fail-closed 不变量未被放宽）")
    void personalWithoutSenderStillRejected() {
        assertThatThrownBy(() -> new IncomingMessage(CHAT, 0L, MSG, "hi",
                IncomingMessage.MediaKind.NONE, null, null, ChatKind.SUPERGROUP))
                .as("userId=0 且未标注频道 → 不知道是谁发的，不得静默放过")
                .isInstanceOf(TggException.class);
    }

    @Test
    @DisplayName("既有便捷构造一律表示「个人消息」——既有调用点语义不变")
    void convenienceConstructorsDefaultToPersonal() {
        assertThat(IncomingMessage.text(CHAT, USER, MSG, "hi").senderIsChannel()).isFalse();
        assertThat(new IncomingMessage(CHAT, USER, MSG, "hi",
                IncomingMessage.MediaKind.NONE, null, null).senderIsChannel()).isFalse();
        assertThat(new IncomingMessage(CHAT, USER, MSG, "hi",
                IncomingMessage.MediaKind.NONE, null, null, ChatKind.PRIVATE).senderIsChannel())
                .isFalse();
    }

    @Test
    @DisplayName("回复目标：textWithReply 承载「这条消息回复了谁」；既有构造 reply 恒为 0（无回复）")
    void replyTargetCarried() {
        IncomingMessage withReply = IncomingMessage.textWithReply(CHAT, USER, MSG, "/kick",
                3003L /* 被回复者 */, 42L /* 被回复消息 */);
        assertThat(withReply.replyToUserId()).isEqualTo(3003L);
        assertThat(withReply.replyToMessageId()).isEqualTo(42L);
        assertThat(withReply.hasReplyUser()).isTrue();
        assertThat(withReply.hasReplyMessage()).isTrue();

        IncomingMessage plain = IncomingMessage.text(CHAT, USER, MSG, "/kick");
        assertThat(plain.replyToUserId()).isZero();
        assertThat(plain.replyToMessageId()).isZero();
        assertThat(plain.hasReplyUser()).isFalse();
        assertThat(plain.hasReplyMessage()).isFalse();
    }

    @Test
    @DisplayName("回复目标两字段独立：只知被回复消息号、被回复者不可知（频道帖等）——仍可承载")
    void replyTargetFieldsIndependent() {
        IncomingMessage messageOnly = IncomingMessage.textWithReply(CHAT, USER, MSG, "/del",
                0L, 42L);
        assertThat(messageOnly.hasReplyUser()).isFalse();
        assertThat(messageOnly.hasReplyMessage()).isTrue();
    }

    @Test
    @DisplayName("原有坐标校验仍然生效：群/消息/媒体种类/聊天类型缺失一律构造期拒")
    void originalCoordinateChecksStillHold() {
        assertThatThrownBy(() -> IncomingMessage.text(0L, USER, MSG, "hi"))
                .isInstanceOf(TggException.class);
        assertThatThrownBy(() -> IncomingMessage.text(CHAT, USER, 0L, "hi"))
                .isInstanceOf(TggException.class);
        assertThatThrownBy(() -> new IncomingMessage(CHAT, USER, MSG, "hi", null, null, null))
                .as("媒体种类缺失")
                .isInstanceOf(TggException.class);
        assertThatThrownBy(() -> new IncomingMessage(CHAT, USER, MSG, "hi",
                IncomingMessage.MediaKind.NONE, null, null, null))
                .as("聊天类型缺失")
                .isInstanceOf(TggException.class);
    }
}
