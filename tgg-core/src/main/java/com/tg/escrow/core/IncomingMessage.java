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
 * <h2>文本可空，身份不可空</h2>
 * <p>{@code text} 允许为 {@code null}（纯图片/文件消息没有文本）；但
 * {@code chatId} / {@code userId} / {@code messageId} 是处置动作的必需坐标，
 * 缺失就没法删消息、没法累计警告——故构造期即拒。
 *
 * @param chatId    群 ID
 * @param userId    发送者 ID
 * @param messageId 消息 ID（处置时要删的就是它）
 * @param text      消息文本（纯媒体消息时为 {@code null}）
 * @param mediaKind 媒体种类
 * @param fileName  文件名（仅文档类媒体有；否则 {@code null}）
 * @param mimeType  MIME 类型（未知时 {@code null}）
 */
public record IncomingMessage(long chatId, long userId, long messageId, String text,
                              MediaKind mediaKind, String fileName, String mimeType) {

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
        if (userId == 0) {
            throw new TggException("入站消息：发送者未提供");
        }
        if (messageId == 0) {
            throw new TggException("入站消息：消息 ID 未提供（处置时无法定位要删的消息）");
        }
        if (mediaKind == null) {
            throw new TggException("入站消息：媒体种类未提供");
        }
    }

    /** 纯文本消息的便捷构造（无媒体、无文件名/类型）。 */
    public static IncomingMessage text(long chatId, long userId, long messageId, String text) {
        return new IncomingMessage(chatId, userId, messageId, text, MediaKind.NONE, null, null);
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
