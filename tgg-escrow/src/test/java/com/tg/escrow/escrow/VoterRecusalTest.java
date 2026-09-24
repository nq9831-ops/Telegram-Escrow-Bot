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
package com.tg.escrow.escrow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 投票人回避（ET-42）的行为固定测试。
 *
 * <p>回避判定的两条线：<b>与买卖双方在窗口内有交易者</b>、以及<b>买卖双方本人</b>。
 * 无关第三方不得被误伤——回避是排除利益关联，不是禁止一切参与。
 */
class VoterRecusalTest {

    private static final long BUYER = 1001L;
    private static final long SELLER = 2002L;

    @Test
    @DisplayName("投票人本人是买方 → 回避")
    void buyerIsRecused() {
        assertThat(VoterRecusal.isRecused(Set.of(9L), BUYER, SELLER, BUYER)).isTrue();
    }

    @Test
    @DisplayName("投票人本人是卖方 → 回避")
    void sellerIsRecused() {
        assertThat(VoterRecusal.isRecused(Set.of(9L), BUYER, SELLER, SELLER)).isTrue();
    }

    @Test
    @DisplayName("近期与买方有交易 → 回避")
    void tradedWithBuyerRecused() {
        assertThat(VoterRecusal.isRecused(Set.of(BUYER), BUYER, SELLER, 555L)).isTrue();
    }

    @Test
    @DisplayName("近期与卖方有交易 → 回避")
    void tradedWithSellerRecused() {
        assertThat(VoterRecusal.isRecused(Set.of(SELLER), BUYER, SELLER, 555L)).isTrue();
    }

    @Test
    @DisplayName("无关第三方（近期只与无关者交易）→ 不回避")
    void unrelatedVoterNotRecused() {
        assertThat(VoterRecusal.isRecused(Set.of(9L, 10L), BUYER, SELLER, 555L)).isFalse();
    }

    @Test
    @DisplayName("近期无任何交易（空集合）→ 不回避")
    void noRecentTradesNotRecused() {
        assertThat(VoterRecusal.isRecused(Set.of(), BUYER, SELLER, 555L)).isFalse();
    }

    @Test
    @DisplayName("对手集合为 null → 抛（不静默当作无交易放行）")
    void nullSetFailsClosed() {
        assertThatThrownBy(() -> VoterRecusal.isRecused(null, BUYER, SELLER, 555L))
                .isInstanceOf(com.tg.escrow.common.EscrowException.class);
    }
}
