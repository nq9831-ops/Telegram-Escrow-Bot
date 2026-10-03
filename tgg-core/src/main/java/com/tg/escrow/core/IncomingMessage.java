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

/**
 * 一条进来的群消息（Wave 1）——内容安全检测器的统一输入。
 *
 * <h2>为什么需要它</h2>
 * <p>检测器需要的不只是文本：媒体过滤要看文件名/类型，处置要看消息号。
 * 此前消息管道的形参只有一个 {@code String text}，于是
 * {@link LinkFilter} / {@link MediaFilter} / {@link RateLimiter} 三个守卫
 * <b>根本没有可用的输入</b>——它们实现完备、有测试，却零生产引用。
 * 把消息元信息载体化，是让它们能真正上场的<b>唯一</b>前提。
 *
 * <h2>发送者身份有两种：个人与频道（GM-12）</h2>
 * <p>群内可以「以频道身份」发帖（Telegram 侧 {@code from} 为空、{@code sender_chat} 有值）。
 * 这类消息<b>没有个人发送者</b>，于是 {@code userId} 只能是 0。此前 {@code userId == 0}
 * 被一律拒绝，于是这类消息在转换阶段就抛异常、<b>整条不被处理</b>——不是"漏判了一条可疑频道帖"，
 * 而是"这类消息从未进入过判定"。故模型显式承载 {@code senderIsChannel} / {@code senderName}：
 * <b>{@code userId == 0} 只在显式标注频道身份时才允许</b>。这不是放宽，是精确化——
 * 既非频道、又报不出 userId 的消息仍然当场拒绝。
 *
 * <h2>文本可空，坐标不可空</h2>
 * <p>{@code text} 允许为 {@code null}（纯图片/文件消息没有文本）；但
 * {@code chatId} / {@code messageId} 是处置动作的必需坐标，缺失就没法删消息——故构造期即拒。
 *
 * @param chatId          群 ID
 * @param userId          发送者 ID（频道帖为 0，见 {@code senderIsChannel}）
 * @param messageId       消息 ID（处置时要删的就是它）
 * @param text            消息文本（纯媒体消息时为 {@code null}）
 * @param mediaKind       媒体种类
 * @param fileName        文件名（仅文档类媒体有；否则 {@code null}）
 * @param mimeType        MIME 类型（未知时 {@code null}）
 * @param chatKind        聊天类型（决定内容安全是否适用；见 {@link ChatKind#moderatable()}）
 * @param senderIsChannel 发送者是否为频道身份
 * @param senderName      频道名（仅频道帖有；无法识别时为 {@code null}）
 * @param replyToUserId   被回复消息的发送者 ID（无回复 / 不可辨识时为 0）——
 *                        「回复即指定目标」的交互（群管理命令与 /escrow create 用它免输数字 ID）
 * @param replyToMessageId 被回复的消息 ID（无回复时为 0）——/del 等按消息操作的命令用它
 */
public record IncomingMessage(long chatId, long userId, long messageId, String text,
                              MediaKind mediaKind, String fileName, String mimeType,
                              ChatKind chatKind, boolean senderIsChannel, String senderName,
                              long replyToUserId, long replyToMessageId) {

    /** 媒体种类。{@code NONE} 表示纯文本消息。 */
    public enum MediaKind {
        /** 无媒体。 */
        NONE,
        /** 图片。 */
        PHOTO,
        /** 文档/文件。 */
        DOCUMENT,
        /** 视频。 */
        VIDEO,
        /** 其它媒体（语音、贴纸等）。 */
        OTHER
    }

    public IncomingMessage {
        if (chatId == 0) {
            throw new TggException("入站消息：群 ID 未提供");
        }
        if (userId == 0 && !senderIsChannel) {
            throw new TggException("入站消息：发送者未提供（userId=0 仅当显式标注发送者为频道时才允许）");
        }
        if (messageId == 0) {
            throw new TggException("入站消息：消息 ID 未提供（处置时无法定位要删的消息）");
        }
        if (mediaKind == null) {
            throw new TggException("入站消息：媒体种类未提供");
        }
        if (chatKind == null) {
            throw new TggException("入站消息：聊天类型未提供（决定内容安全是否适用）");
        }
    }

    /**
     * 便捷构造（10 参，既有形态）：<b>不带回复目标</b>（reply 恒为 0）。
     *
     * <p>保留它让既有调用点（含大量测试）零改动；需要回复目标的路径用 12 参规范构造
     * （生产点 {@code TelegramBotHandler.toIncoming}）或 {@link #textWithReply}。
     */
    public IncomingMessage(long chatId, long userId, long messageId, String text,
                           MediaKind mediaKind, String fileName, String mimeType,
                           ChatKind chatKind, boolean senderIsChannel, String senderName) {
        this(chatId, userId, messageId, text, mediaKind, fileName, mimeType,
                chatKind, senderIsChannel, senderName, 0L, 0L);
    }

    /**
     * 便捷构造：不带聊天类型时按<b>超级群</b>处理（群聊语义）。
     *
     * <p>刻意取群聊而非"未知"：内容安全是"宁可多判不可漏判"的方向，且需要显式指定类型的
     * 真实路径（{@code TelegramBotHandler.toIncoming}）总会给出类型。测试里用这个构造
     * 等价于"测群聊行为"，可读性更好。
     */
    public IncomingMessage(long chatId, long userId, long messageId, String text,
                           MediaKind mediaKind, String fileName, String mimeType) {
        this(chatId, userId, messageId, text, mediaKind, fileName, mimeType, ChatKind.SUPERGROUP);
    }

    /**
     * 个人消息便捷构造（既有语义）：发送者不是频道。
     *
     * <p>刻意<b>不</b>把"频道身份"做成一个有默认值的可选参数再让生产路径"忘了传"——
     * 生产只有一处调用点（{@code TelegramBotHandler.toIncoming}），它必须显式表达身份；
     * 本构造只服务"这条消息来自个人"的既有调用点与测试。
     */
    public IncomingMessage(long chatId, long userId, long messageId, String text,
                           MediaKind mediaKind, String fileName, String mimeType, ChatKind chatKind) {
        this(chatId, userId, messageId, text, mediaKind, fileName, mimeType, chatKind, false, null);
    }

    /**
     * 频道帖便捷构造：无个人发送者，身份由频道名承载（GM-12）。
     *
     * @param channelName 频道名；无法识别时给 {@code null}——由守卫按「无法识别即拦」处理，
     *                    不在这里判死（"拦不拦"取决于该通道是否启用了这项防御）
     */
    public static IncomingMessage channelPost(long chatId, long messageId, String text,
                                              MediaKind mediaKind, String fileName, String mimeType,
                                              ChatKind chatKind, String channelName) {
        return new IncomingMessage(chatId, 0L, messageId, text, mediaKind, fileName, mimeType,
                chatKind, true, channelName);
    }

    /** 纯文本消息的便捷构造（无媒体、无文件名/类型；按群聊处理）。 */
    public static IncomingMessage text(long chatId, long userId, long messageId, String text) {
        return new IncomingMessage(chatId, userId, messageId, text, MediaKind.NONE, null, null,
                ChatKind.SUPERGROUP);
    }

    /** 纯文本消息便捷构造（带回复目标；按群聊处理）——「回复即指定目标」的入口。 */
    public static IncomingMessage textWithReply(long chatId, long userId, long messageId,
                                                String text, long replyToUserId,
                                                long replyToMessageId) {
        return new IncomingMessage(chatId, userId, messageId, text, MediaKind.NONE, null, null,
                ChatKind.SUPERGROUP, false, null, replyToUserId, replyToMessageId);
    }

    /** 是否回复了某个可辨识的用户（被回复者 ID 已知）。 */
    public boolean hasReplyUser() {
        return replyToUserId > 0;
    }

    /** 是否回复了某条消息（消息号已知）——/del 等按消息操作的命令据此工作。 */
    public boolean hasReplyMessage() {
        return replyToMessageId > 0;
    }

    /** 是否带媒体（{@code NONE} 之外都算）。 */
    public boolean hasMedia() {
        return mediaKind != MediaKind.NONE;
    }

    /**
     * 是否有可供 {@link MediaFilter} 判定的文件信息。
     *
     * <p>仅当文件名或 MIME 至少有一个非空白时为真。图片消息在 Telegram 侧通常不带
     * 文件名与 MIME，此时<b>不做</b>媒体白名单判定——不拿空值去猜，也不因此误杀。
     */
    public boolean hasFileInfo() {
        return notBlank(fileName) || notBlank(mimeType);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
