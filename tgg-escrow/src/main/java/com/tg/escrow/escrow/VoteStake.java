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

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 投票质押（核心规则「仲裁员质押 1-5 USDT，投错没收」）。
 *
 * <h2>三态与资金守卫</h2>
 * <pre>
 * LOCKED ──forfeit──▶ FORFEITED（终态：没收，进分层分配 VoteRewardAllocator）
 *    └─────returnToVoter──▶ RETURNED（终态：退还）
 * </pre>
 * <p>质押是真金白银的托管——状态漂移即资金漂移，所以：
 * <ul>
 *   <li>金额范围 <b>1–5 USDT 含两端</b>（{@link BigDecimal#compareTo} 比较，不受 scale 影响）；</li>
 *   <li>终态不可再迁移（没收后不得"改口"退还）；</li>
 *   <li>非法迁移抛异常，且<b>不改变状态</b>。</li>
 * </ul>
 *
 * <p>本类有状态、非线程安全：一笔质押由单一处理线程推进。
 */
public final class VoteStake {

    /** 质押状态。 */
    public enum State {
        /** 已锁定。 */
        LOCKED,
        /** 已没收（终态）。 */
        FORFEITED,
        /** 已退还（终态）。 */
        RETURNED
    }

    /** 最低质押（USDT）。 */
    public static final BigDecimal MIN = BigDecimal.ONE;
    /** 最高质押（USDT）。 */
    public static final BigDecimal MAX = new BigDecimal("5");

    private final long voterId;
    private final BigDecimal amount;
    private final Instant lockedAt;
    private State state = State.LOCKED;

    /**
     * @param voterId  投票人
     * @param amount   质押金额（1–5 USDT）
     * @param lockedAt 锁定时刻
     */
    public VoteStake(long voterId, BigDecimal amount, Instant lockedAt) {
        if (amount == null) {
            throw new EscrowException("投票质押：金额未提供");
        }
        if (amount.compareTo(MIN) < 0 || amount.compareTo(MAX) > 0) {
            throw new EscrowException("投票质押：金额必须在 " + MIN + "–" + MAX + " USDT，实为 " + amount);
        }
        if (lockedAt == null) {
            throw new EscrowException("投票质押：锁定时刻未提供");
        }
        this.voterId = voterId;
        this.amount = amount;
        this.lockedAt = lockedAt;
    }

    public long voterId() {
        return voterId;
    }

    public BigDecimal amount() {
        return amount;
    }

    public Instant lockedAt() {
        return lockedAt;
    }

    public State currentState() {
        return state;
    }

    /** 投错没收：{@code LOCKED} → {@code FORFEITED}。 */
    public void forfeit(Instant now) {
        require(State.LOCKED, "没收质押");
        state = State.FORFEITED;
    }

    /** 退还：{@code LOCKED} → {@code RETURNED}。 */
    public void returnToVoter(Instant now) {
        require(State.LOCKED, "退还质押");
        state = State.RETURNED;
    }

    private void require(State expected, String action) {
        if (state != expected) {
            throw new EscrowException("投票质押：" + action + " 非法——当前 " + state + "，要求 " + expected);
        }
    }
}
