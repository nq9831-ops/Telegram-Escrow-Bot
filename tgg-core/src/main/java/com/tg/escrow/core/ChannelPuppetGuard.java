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

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 频道傀儡攻击防御（GM-12）：以频道名义发送的消息，非白名单一律删除。
 *
 * <h2>fail-closed：无法识别即拦</h2>
 * <p>攻击面是"冒用频道身份发消息"绕过个人身份约束。判定方向定死：
 * <b>非白名单频道、频道名空白/缺失 → 删</b>——宁可拦一条正当频道消息，不可放过一次冒名。
 * 白名单为空时全拦（全拦是安全方向；全放等于关掉防御）。
 *
 * <p>白名单匹配容错：忽略大小写与 {@code @} 前缀（Telegram 频道名的常见书写差异）。
 * 个人消息不归本守卫管。纯函数逻辑 + 构造期登记白名单。
 */
public final class ChannelPuppetGuard {

    private final Set<String> whitelist;

    /**
     * @param whitelistedChannels 允许发言的频道名（不得为 {@code null}；空集 = 全拦）
     */
    public ChannelPuppetGuard(Set<String> whitelistedChannels) {
        if (whitelistedChannels == null) {
            throw new TggException("频道防御：白名单不可为空引用（须显式给出集合，空集表示全拦）");
        }
        Set<String> normalized = new HashSet<>();
        for (String name : whitelistedChannels) {
            String n = normalize(name);
            if (n != null) {
                normalized.add(n);
            }
        }
        this.whitelist = Set.copyOf(normalized);
    }

    /**
     * 该频道消息是否应删除。
     *
     * @param senderIsChannel 发送者是否为频道身份
     * @param channelName     频道名（可含 {@code @} 前缀）
     * @return 频道消息且不在白名单（含无法识别）时 {@code true}
     */
    public boolean shouldDelete(boolean senderIsChannel, String channelName) {
        if (!senderIsChannel) {
            return false;
        }
        String normalized = normalize(channelName);
        return normalized == null || !whitelist.contains(normalized);
    }

    /** 归一化：去 {@code @} 前缀、trim、小写；空白返回 {@code null}（无法识别）。 */
    private static String normalize(String channelName) {
        if (channelName == null) {
            return null;
        }
        String s = channelName.trim();
        if (s.startsWith("@")) {
            s = s.substring(1);
        }
        return s.isEmpty() ? null : s.toLowerCase(Locale.ROOT);
    }
}
