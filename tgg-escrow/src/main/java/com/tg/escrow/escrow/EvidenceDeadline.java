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
 * 证据提交窗口（ET-45）：争议发起后固定时限内提交证据，超时视为放弃。
 *
 * <h2>刻意不提供暂停入口</h2>
 * <p>业务冲突 2.3 的解法：<b>证据窗口不随维护期倒计时暂停</b>。
 * 维护期暂停（见 {@link DisputeFlow#pausedRemaining}）是另一条时间线，两者互不干扰。
 * 因此本类<b>没有任何 pause/resume API</b>——窗口只从争议发起时刻起算，
 * {@link #deadlineAt()} 恒等于 {@code 发起时刻 + 窗口}。这不是偷懒，是把"不可暂停"固化成结构约束。
 *
 * <p>到期边界为<b>闭区间</b>（{@code now >= 发起时刻 + 窗口} 即超时视为放弃）。
 * 纯函数、无状态。
 */
public final class EvidenceDeadline {

    private final Instant disputeOpenedAt;
    private final Duration window;

    /**
     * @param disputeOpenedAt 争议发起时刻
     * @param window          提交窗口（正数，典型值 24 小时）
     */
    public EvidenceDeadline(Instant disputeOpenedAt, Duration window) {
        if (disputeOpenedAt == null) {
            throw new EscrowException("证据窗口：争议发起时刻未提供");
        }
        if (window == null || window.isZero() || window.isNegative()) {
            throw new EscrowException("证据窗口：窗口时长必须为正，实为 " + window);
        }
        this.disputeOpenedAt = disputeOpenedAt;
        this.window = window;
    }

    /** 截止时刻（{@code 发起时刻 + 窗口}；无暂停语义）。 */
    public Instant deadlineAt() {
        return disputeOpenedAt.plus(window);
    }

    /** 是否已过期（{@code now >= 截止}，闭区间）——过期即视为放弃举证。 */
    public boolean isExpired(Instant now) {
        requireNow(now);
        return !now.isBefore(deadlineAt());
    }

    /** 此刻是否仍可提交。 */
    public boolean canSubmit(Instant now) {
        return !isExpired(now);
    }

    private static void requireNow(Instant now) {
        if (now == null) {
            throw new EscrowException("证据窗口：当前时刻不可为空");
        }
    }
}
