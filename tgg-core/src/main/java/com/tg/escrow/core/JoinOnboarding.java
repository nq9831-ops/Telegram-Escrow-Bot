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

import java.time.Instant;
import java.util.Arrays;

/**
 * 首次使用引导（GM-35）：新用户第一次使用 Bot 的引导流程状态机。
 *
 * <pre>
 * STARTED ──startGuiding──▶ GUIDING ──complete──▶ COMPLETED（终态）
 *    │                        └───────skip─────▶ SKIPPED（终态）
 *    └───────────────skip─────────────────────▶ SKIPPED
 * </pre>
 *
 * <p>跳过是用户的合法选择（STARTED/GUIDING 均可跳）；完成与跳过均为终态——
 * 引导不得反复骚扰（终态后任何操作抛异常）。
 *
 * <p>有状态、非线程安全：单个用户的引导由单一处理线程推进。
 */
public final class JoinOnboarding {

    /** 引导状态。 */
    public enum State {
        /** 已开始（尚未进入引导）。 */
        STARTED,
        /** 引导中。 */
        GUIDING,
        /** 已完成（终态）。 */
        COMPLETED,
        /** 已跳过（终态）。 */
        SKIPPED
    }

    private final long userId;
    private final Instant startedAt;
    private State state = State.STARTED;

    /**
     * @param userId    新用户 ID
     * @param startedAt 引导开始时刻
     */
    public JoinOnboarding(long userId, Instant startedAt) {
        if (startedAt == null) {
            throw new TggException("首次引导：开始时刻未提供");
        }
        this.userId = userId;
        this.startedAt = startedAt;
    }

    public long userId() {
        return userId;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public State currentState() {
        return state;
    }

    /** 进入引导：{@code STARTED} → {@code GUIDING}。 */
    public void startGuiding(Instant now) {
        require(State.STARTED, "进入引导");
        state = State.GUIDING;
    }

    /** 引导完成：{@code GUIDING} → {@code COMPLETED}。 */
    public void complete(Instant now) {
        require(State.GUIDING, "完成引导");
        state = State.COMPLETED;
    }

    /** 跳过引导：{@code STARTED} 或 {@code GUIDING} → {@code SKIPPED}。 */
    public void skip(Instant now) {
        if (state != State.STARTED && state != State.GUIDING) {
            throw new TggException("首次引导：跳过非法——当前 " + state
                    + Arrays.toString(State.values()));
        }
        state = State.SKIPPED;
    }

    private void require(State expected, String action) {
        if (state != expected) {
            throw new TggException("首次引导：" + action + " 非法——当前 " + state + "，要求 " + expected);
        }
    }
}
