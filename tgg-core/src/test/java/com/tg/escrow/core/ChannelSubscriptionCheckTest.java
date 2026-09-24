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

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 频道订阅前置验证（GM-16/T60）的行为固定测试。
 *
 * <p>业务冲突 1.5 的定案在此钉住：<b>只允许订阅白名单频道</b>（白名单须通过傀儡防御校验）——
 * 配置了非白名单的必订频道即配置错误（fail-closed 抛出），否则用户被迫订阅傀儡频道。
 * 频道名匹配容错与 {@code ChannelPuppetGuard} 同族（大小写 / {@code @} 前缀）。
 */
class ChannelSubscriptionCheckTest {

    private static final Set<String> WHITELIST = Set.of("official-news", "rules");

    @Test
    @DisplayName("全部必订频道已订阅 → 通过")
    void allRequiredSubscribed() {
        ChannelSubscriptionCheck c = new ChannelSubscriptionCheck(WHITELIST);

        assertThat(c.passes(Set.of("official-news", "rules"), Set.of("official-news", "rules"))).isTrue();
    }

    @Test
    @DisplayName("缺任一必订频道 → 不通过")
    void missingRequiredFails() {
        ChannelSubscriptionCheck c = new ChannelSubscriptionCheck(WHITELIST);

        assertThat(c.passes(Set.of("official-news"), Set.of("official-news", "rules"))).isFalse();
    }

    @Test
    @DisplayName("必订频道含非白名单 → 配置错误抛出（防强迫订阅傀儡频道，冲突 1.5 定案）")
    void requiredOutsideWhitelistRejected() {
        ChannelSubscriptionCheck c = new ChannelSubscriptionCheck(WHITELIST);

        assertThatThrownBy(() -> c.passes(Set.of(), Set.of("evil-channel")))
                .isInstanceOf(TggException.class);
    }

    @Test
    @DisplayName("频道名匹配容错：大小写与 @ 前缀")
    void nameMatchingTolerant() {
        ChannelSubscriptionCheck c = new ChannelSubscriptionCheck(Set.of("Official-News"));

        assertThat(c.passes(Set.of("@official-news"), Set.of("Official-News"))).isTrue();
    }

    @Test
    @DisplayName("null 输入 → fail-closed")
    void nullsRejected() {
        ChannelSubscriptionCheck c = new ChannelSubscriptionCheck(WHITELIST);

        assertThatThrownBy(() -> c.passes(null, Set.of())).isInstanceOf(TggException.class);
        assertThatThrownBy(() -> new ChannelSubscriptionCheck(null)).isInstanceOf(TggException.class);
    }
}
