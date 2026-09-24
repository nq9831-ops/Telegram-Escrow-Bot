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

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * 交易超时策略（原文档 T13 / T27）：按「状态 + 进入时刻 + 当前时刻」判定是否超时，
 * 并给出应触发的动作（自动确认 / 自动退款）。
 *
 * <h2>为什么超时"动作"不可省</h2>
 * <p>超时本身不是目的——它必须落到一个资金动作上（{@code DELIVERED} 超时自动放款、
 * {@code LOCKED} 超时自动退款）。只报"超时了"而不指定动作，会把"钱往哪走"留给调用方猜。
 *
 * <h2>未登记规则的终态永不超时</h2>
 * <p>终态（{@code RELEASED}/{@code REFUNDED}/{@code CANCELLED}）不该出现在规则表里；
 * 即使被误登记，{@link #check} 对未登记状态也返回"不动作"——绝不让超时逻辑二次处理终态。
 *
 * <p>到期边界为<b>闭区间</b>：{@code now >= deadline} 即超时。
 *
 * <h2>并发</h2>
 * <p>无状态、只读规则表，可安全并发调用。
 */
public final class TradeTimeoutPolicy {

    /** 超时触发后的资金动作。 */
    public enum TimeoutAction {
        /** 不动作。 */
        NONE,
        /** 自动确认（放款给卖方）。 */
        AUTO_CONFIRM,
        /** 自动退款（退还给买方）。 */
        AUTO_REFUND
    }

    /**
     * 单个状态的超时规则。
     *
     * @param timeout 允许停留的时长（正数）
     * @param action  超时后触发的动作（不可为 {@link TimeoutAction#NONE}）
     */
    public record TimeoutRule(Duration timeout, TimeoutAction action) {
        public TimeoutRule {
            if (timeout == null || timeout.isZero() || timeout.isNegative()) {
                throw new EscrowException("超时规则：时长必须为正，实为 " + timeout);
            }
            if (action == null || action == TimeoutAction.NONE) {
                throw new EscrowException("超时规则：必须指定一个真实的超时动作");
            }
        }
    }

    /**
     * 超时判定结果。
     *
     * @param timedOut 是否已超时
     * @param action   应触发的动作（未超时恒为 {@link TimeoutAction#NONE}）
     */
    public record TimeoutDecision(boolean timedOut, TimeoutAction action) {

        /** 未超时。 */
        public static TimeoutDecision none() {
            return new TimeoutDecision(false, TimeoutAction.NONE);
        }

        /** 已超时并触发动作。 */
        public static TimeoutDecision trigger(TimeoutAction action) {
            if (action == null || action == TimeoutAction.NONE) {
                throw new EscrowException("超时判定：触发动作不可为空或 NONE");
            }
            return new TimeoutDecision(true, action);
        }
    }

    private final Map<EscrowOrder.State, TimeoutRule> rules;

    /**
     * @param rules 状态 → 超时规则；{@code null} 时 fail-closed
     */
    public TradeTimeoutPolicy(Map<EscrowOrder.State, TimeoutRule> rules) {
        if (rules == null) {
            throw new EscrowException("超时策略：未提供规则表");
        }
        this.rules = Map.copyOf(rules);
    }

    /**
     * 判定某状态是否超时。
     *
     * @param state     订单当前状态
     * @param enteredAt 进入该状态的时刻
     * @param now       当前时刻
     * @return 超时判定结果；未登记规则的状态恒返回不动作
     */
    public TimeoutDecision check(EscrowOrder.State state, Instant enteredAt, Instant now) {
        if (state == null) {
            throw new EscrowException("超时策略：状态不可为空");
        }
        if (enteredAt == null || now == null) {
            throw new EscrowException("超时策略：时刻不可为空");
        }
        TimeoutRule rule = rules.get(state);
        if (rule == null) {
            return TimeoutDecision.none();
        }
        Instant deadline = enteredAt.plus(rule.timeout());
        if (!now.isBefore(deadline)) {
            return TimeoutDecision.trigger(rule.action());
        }
        return TimeoutDecision.none();
    }
}
