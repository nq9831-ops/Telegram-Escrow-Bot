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

/**
 * 争议流程守卫（原文档 T14 / T28）。
 *
 * <h2>两道守卫</h2>
 * <ol>
 *   <li><b>谁能发</b>：仅买卖双方。第三方发起争议等于让路人冻结他人的钱。</li>
 *   <li><b>何时能发</b>：仅 {@code LOCKED}（资金已托管）或 {@code DELIVERED}（已交付）。
 *       资金未托管时无争议对象，终态更不该再起争议。</li>
 * </ol>
 *
 * <h2>维护期倒计时的暂停（T28）</h2>
 * <p>争议一旦发起，维护期倒计时应暂停——否则争议还在裁决、维护期却已走完，
 * 用户会失去售后窗口。{@link #pausedRemaining} 给出"暂停那一刻还剩多少"，
 * 裁决结束后以此续算。
 */
public final class DisputeFlow {

    private DisputeFlow() {
    }

    /**
     * 某人此时能否对订单发起争议。
     *
     * @param order   订单
     * @param actorId 发起人
     * @return 状态与身份都满足时为 {@code true}
     */
    public static boolean canInitiate(EscrowOrder order, long actorId) {
        if (order == null) {
            throw new EscrowException("争议流程：订单不可为空");
        }
        EscrowOrder.State state = order.currentState();
        if (state != EscrowOrder.State.LOCKED && state != EscrowOrder.State.DELIVERED) {
            return false;
        }
        return actorId == order.getBuyerUserId() || actorId == order.getSellerUserId();
    }

    /**
     * 同 {@link #canInitiate}，但不满足时抛异常——供"必须通过"的调用点使用。
     *
     * @throws EscrowException 状态不允许、或发起人非买卖双方
     */
    public static void requireCanInitiate(EscrowOrder order, long actorId) {
        if (order == null) {
            throw new EscrowException("争议流程：订单不可为空");
        }
        EscrowOrder.State state = order.currentState();
        if (state != EscrowOrder.State.LOCKED && state != EscrowOrder.State.DELIVERED) {
            throw new EscrowException("争议流程：当前状态 " + state
                    + " 不允许发起争议（仅 LOCKED/DELIVERED）");
        }
        if (actorId != order.getBuyerUserId() && actorId != order.getSellerUserId()) {
            throw new EscrowException("争议流程：仅买卖双方可发起争议");
        }
    }

    /**
     * 计算维护期暂停时的剩余时长。
     *
     * @param maintenanceDeadline 维护期原截止时刻
     * @param disputeAt           争议发起时刻
     * @return {@code disputeAt} 到截止的剩余时长；若发起时维护期已过（或恰到期）则为 {@link Duration#ZERO}
     */
    public static Duration pausedRemaining(Instant maintenanceDeadline, Instant disputeAt) {
        if (maintenanceDeadline == null || disputeAt == null) {
            throw new EscrowException("争议流程：时刻不可为空");
        }
        if (!disputeAt.isBefore(maintenanceDeadline)) {
            return Duration.ZERO;
        }
        return Duration.between(disputeAt, maintenanceDeadline);
    }
}
