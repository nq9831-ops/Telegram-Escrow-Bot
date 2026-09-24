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
 * 频道傀儡攻击防御（GM-12）的行为固定测试。
 *
 * <p>攻击面：以频道名义发消息绕过个人身份约束。防御语义 <b>fail-closed</b>：
 * 非白名单频道消息一律删——宁可拦一条正当频道消息，不可放过一次冒名。
 * 白名单按频道名匹配（大小写不敏感、容忍 @ 前缀）。
 */
class ChannelPuppetGuardTest {

    @Test
    @DisplayName("频道消息且不在白名单 → 删除")
    void nonWhitelistedChannelDeleted() {
        ChannelPuppetGuard g = new ChannelPuppetGuard(Set.of("trusted"));

        assertThat(g.shouldDelete(true, "evil")).isTrue();
    }

    @Test
    @DisplayName("白名单频道 → 放行")
    void whitelistedChannelAllowed() {
        ChannelPuppetGuard g = new ChannelPuppetGuard(Set.of("trusted"));

        assertThat(g.shouldDelete(true, "trusted")).isFalse();
    }

    @Test
    @DisplayName("白名单匹配大小写不敏感、容忍 @ 前缀")
    void whitelistMatchingTolerant() {
        ChannelPuppetGuard g = new ChannelPuppetGuard(Set.of("TrustedChannel"));

        assertThat(g.shouldDelete(true, "@trustedchannel")).isFalse();
        assertThat(g.shouldDelete(true, "TRUSTEDCHANNEL")).isFalse();
    }

    @Test
    @DisplayName("普通用户消息不归本守卫管（不删）")
    void personalMessageUntouched() {
        ChannelPuppetGuard g = new ChannelPuppetGuard(Set.of());

        assertThat(g.shouldDelete(false, "someone")).isFalse();
    }

    @Test
    @DisplayName("频道名空白 / 无法识别 → 删除（无法识别即拦，fail-closed）")
    void unidentifiableChannelDeleted() {
        ChannelPuppetGuard g = new ChannelPuppetGuard(Set.of("trusted"));

        assertThat(g.shouldDelete(true, "")).isTrue();
        assertThat(g.shouldDelete(true, null)).isTrue();
    }

    @Test
    @DisplayName("白名单为空 → 所有频道消息都删（全拦，非全放）")
    void emptyWhitelistBlocksAllChannels() {
        ChannelPuppetGuard g = new ChannelPuppetGuard(Set.of());

        assertThat(g.shouldDelete(true, "any")).isTrue();
    }

    @Test
    @DisplayName("白名单为 null → 构造期拒绝（不静默当作空集）")
    void nullWhitelistRejected() {
        assertThatThrownBy(() -> new ChannelPuppetGuard(null)).isInstanceOf(TggException.class);
    }
}
