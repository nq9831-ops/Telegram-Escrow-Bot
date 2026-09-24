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

import com.tg.escrow.common.EscrowException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 争议流程（T14 / T28）的行为固定测试。
 *
 * <p>争议是资金归属的转折点，所以两道守卫必须钉死：<b>谁能发</b>（仅买卖双方）、
 * <b>何时能发</b>（仅资金已托管或已交付）。此外维护期倒计时的暂停时刻必须可复算——
 * 它决定争议解决后还剩多少维护时间。
 */
class DisputeFlowTest {

    private static final long BUYER = 1001L;
    private static final long SELLER = 2002L;
    private static final long STRANGER = 9999L;
    private static final Instant T0 = Instant.parse("2026-09-23T00:00:00Z");

    private static EscrowOrder order() {
        return new EscrowOrder(BUYER, SELLER, new BigDecimal("100"), "USDT", T0);
    }

    @Test
    @DisplayName("LOCKED 状态下买卖双方均可发起争议")
    void bothPartiesCanDisputeWhenLocked() {
        EscrowOrder o = order();
        o.markLocked(T0);

        assertThat(DisputeFlow.canInitiate(o, BUYER)).isTrue();
        assertThat(DisputeFlow.canInitiate(o, SELLER)).isTrue();
    }

    @Test
    @DisplayName("DELIVERED 状态下仍可发起争议（交付后仍可争议）")
    void disputeAllowedAfterDelivery() {
        EscrowOrder o = order();
        o.markLocked(T0);
        o.markDelivered(T0);

        assertThat(DisputeFlow.canInitiate(o, BUYER)).isTrue();
    }

    @Test
    @DisplayName("非当事方不得发起争议")
    void strangerCannotDispute() {
        EscrowOrder o = order();
        o.markLocked(T0);

        assertThat(DisputeFlow.canInitiate(o, STRANGER)).isFalse();
        assertThatThrownBy(() -> DisputeFlow.requireCanInitiate(o, STRANGER))
                .isInstanceOf(EscrowException.class);
    }

    @Test
    @DisplayName("资金未托管（OPEN）不得发起争议")
    void cannotDisputeBeforeLock() {
        EscrowOrder o = order();

        assertThat(DisputeFlow.canInitiate(o, BUYER)).isFalse();
        assertThatThrownBy(() -> DisputeFlow.requireCanInitiate(o, BUYER))
                .isInstanceOf(EscrowException.class);
    }

    @Test
    @DisplayName("终态（RELEASED）不得再发起争议")
    void cannotDisputeAfterRelease() {
        EscrowOrder o = order();
        o.markLocked(T0);
        o.markReleased(T0);

        assertThat(DisputeFlow.canInitiate(o, BUYER)).isFalse();
    }

    @Test
    @DisplayName("维护期暂停时刻：返回争议发起时距维护期截止的剩余时长")
    void pausedRemainingIsComputed() {
        Instant deadline = T0.plus(Duration.ofDays(7));
        Instant disputeAt = T0.plus(Duration.ofDays(2));

        assertThat(DisputeFlow.pausedRemaining(deadline, disputeAt))
                .isEqualTo(Duration.ofDays(5));
    }

    @Test
    @DisplayName("维护期已过期后才发起争议 → 剩余为零（无从暂停）")
    void pausedRemainingIsZeroWhenAlreadyExpired() {
        Instant deadline = T0.plus(Duration.ofDays(7));
        Instant disputeAt = T0.plus(Duration.ofDays(8));

        assertThat(DisputeFlow.pausedRemaining(deadline, disputeAt)).isZero();
    }
}
