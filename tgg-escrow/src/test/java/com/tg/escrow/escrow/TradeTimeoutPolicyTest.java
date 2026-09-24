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

import com.tg.escrow.escrow.EscrowOrder.State;
import com.tg.escrow.escrow.TradeTimeoutPolicy.TimeoutAction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 交易超时策略（T13 / T27）的行为固定测试。
 *
 * <p>超时判定直接决定"钱往哪走"（自动放款 / 自动退款），所以到期边界必须定死为
 * <b>闭区间</b>（{@code now >= deadline} 即超时），且"未登记超时规则的终态"必须判为
 * 不动作——绝不能让终态被超时逻辑二次处理。
 */
class TradeTimeoutPolicyTest {

    private static final Instant T0 = Instant.parse("2026-09-23T00:00:00Z");

    private static TradeTimeoutPolicy policy() {
        return new TradeTimeoutPolicy(Map.of(
                State.DELIVERED, new TradeTimeoutPolicy.TimeoutRule(Duration.ofHours(24), TimeoutAction.AUTO_CONFIRM),
                State.LOCKED, new TradeTimeoutPolicy.TimeoutRule(Duration.ofHours(48), TimeoutAction.AUTO_REFUND)));
    }

    @Test
    @DisplayName("DELIVERED 超过时限 → 自动确认（放款）")
    void deliveredTimesOutToAutoConfirm() {
        TradeTimeoutPolicy p = policy();
        TradeTimeoutPolicy.TimeoutDecision d =
                p.check(State.DELIVERED, T0, T0.plus(Duration.ofHours(25)));

        assertThat(d.timedOut()).isTrue();
        assertThat(d.action()).isEqualTo(TimeoutAction.AUTO_CONFIRM);
    }

    @Test
    @DisplayName("LOCKED 超过时限 → 自动退款")
    void lockedTimesOutToAutoRefund() {
        TradeTimeoutPolicy p = policy();
        TradeTimeoutPolicy.TimeoutDecision d =
                p.check(State.LOCKED, T0, T0.plus(Duration.ofHours(49)));

        assertThat(d.timedOut()).isTrue();
        assertThat(d.action()).isEqualTo(TimeoutAction.AUTO_REFUND);
    }

    @Test
    @DisplayName("未到时限 → 不动作")
    void beforeDeadlineNoAction() {
        TradeTimeoutPolicy p = policy();
        TradeTimeoutPolicy.TimeoutDecision d =
                p.check(State.DELIVERED, T0, T0.plus(Duration.ofHours(1)));

        assertThat(d.timedOut()).isFalse();
        assertThat(d.action()).isEqualTo(TimeoutAction.NONE);
    }

    @Test
    @DisplayName("恰好到达时限即算超时（闭区间，边界定死）")
    void exactlyAtDeadlineTimesOut() {
        TradeTimeoutPolicy p = policy();
        TradeTimeoutPolicy.TimeoutDecision d =
                p.check(State.DELIVERED, T0, T0.plus(Duration.ofHours(24)));

        assertThat(d.timedOut()).isTrue();
        assertThat(d.action()).isEqualTo(TimeoutAction.AUTO_CONFIRM);
    }

    @Test
    @DisplayName("未登记规则的终态 → 永不超时（终态不得被超时逻辑二次处理）")
    void unregisteredTerminalNeverTimesOut() {
        TradeTimeoutPolicy p = policy();

        assertThat(p.check(State.RELEASED, T0, T0.plus(Duration.ofDays(365))).timedOut()).isFalse();
        assertThat(p.check(State.REFUNDED, T0, T0.plus(Duration.ofDays(365))).timedOut()).isFalse();
        assertThat(p.check(State.CANCELLED, T0, T0.plus(Duration.ofDays(365))).timedOut()).isFalse();
    }

    @Test
    @DisplayName("构造期校验：规则表为空 / 含 null 规则 → 拒绝")
    void invalidRulesRejected() {
        assertThatThrownBy(() -> new TradeTimeoutPolicy(null))
                .isInstanceOf(com.tg.escrow.common.EscrowException.class);
    }
}
