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

import java.util.Locale;
import java.util.Set;

/**
 * 频道订阅前置验证（GM-16/T60）：入群需订阅指定白名单频道。
 *
 * <h2>业务冲突 1.5 的定案</h2>
 * <p><b>只允许订阅白名单频道</b>（白名单由群主配置、且须通过频道傀儡防御校验）：
 * 必订频道若含非白名单项，属配置错误（fail-closed 抛出）——否则用户会被迫订阅傀儡频道，
 * 验证通过后频道消息又被傀儡防御删除，流程自相矛盾。
 *
 * <p>频道名匹配容错与 {@link ChannelPuppetGuard} 同族（大小写 / {@code @} 前缀）。纯函数。
 */
public final class ChannelSubscriptionCheck {

    private final Set<String> whitelist;

    /**
     * @param whitelist 允许订阅的频道（经傀儡防御校验；不得为 {@code null}）
     */
    public ChannelSubscriptionCheck(Set<String> whitelist) {
        if (whitelist == null) {
            throw new TggException("频道订阅：白名单不可为空引用");
        }
        Set<String> normalized = new java.util.HashSet<>();
        for (String name : whitelist) {
            String n = normalize(name);
            if (n != null) {
                normalized.add(n);
            }
        }
        this.whitelist = Set.copyOf(normalized);
    }

    /**
     * 订阅检查：必订频道全部已订阅。
     *
     * @param subscribedChannels 用户已订阅频道
     * @param requiredChannels   必订频道（<b>必须全部在白名单内</b>，否则配置错误）
     * @return 全部必订频道均已订阅时 {@code true}
     */
    public boolean passes(Set<String> subscribedChannels, Set<String> requiredChannels) {
        if (subscribedChannels == null || requiredChannels == null) {
            throw new TggException("频道订阅：订阅列表与必订列表均不可为 null（空集请传 Set.of()）");
        }
        for (String required : requiredChannels) {
            String normalized = normalize(required);
            if (normalized == null) {
                throw new TggException("频道订阅：必订频道名不得空白");
            }
            if (!whitelist.contains(normalized)) {
                // 配置错误：必订非白名单频道会迫使用户订阅傀儡频道——拒绝而非静默放行
                throw new TggException("频道订阅：必订频道 " + required + " 不在白名单——配置错误");
            }
        }
        Set<String> subscribed = new java.util.HashSet<>();
        for (String name : subscribedChannels) {
            String n = normalize(name);
            if (n != null) {
                subscribed.add(n);
            }
        }
        for (String required : requiredChannels) {
            if (!subscribed.contains(normalize(required))) {
                return false;
            }
        }
        return true;
    }

    /** 归一化：去 {@code @} 前缀、trim、小写；空白返回 {@code null}。 */
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
