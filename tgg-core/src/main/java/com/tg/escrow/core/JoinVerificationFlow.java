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

import java.time.Duration;
import java.time.Instant;

/**
 * 入群验证流程（原文档 G12/G13）：申请 → 验证中 → 通过 / 拒绝 / 超时。
 *
 * <h2>四态与守卫</h2>
 * <pre>
 * APPLIED ──startVerifying──▶ VERIFYING ──approve──▶ APPROVED（终态）
 *    │                            └──────reject───▶ REJECTED（终态）
 *    └────────── 超时 ────────────┴────▶ TIMED_OUT（终态）
 * </pre>
 *
 * <p>只做状态与守卫，不含"谁来验证""怎么验证"——那是装配层的职责。
 * 交互层（Mini App 问答）依赖 S1，另行接入。
 *
 * <h2>超时定死为闭区间</h2>
 * <p>{@code now >= appliedAt + timeout} 即超时（与项目内其它超时判定一致）。
 * 终态（含 APPROVED）不被超时逻辑改写——已通过的申请不该因时间流逝变成"超时"。
 *
 * <p>本类有状态、非线程安全：单个申请由一个处理线程推进即可。
 */
public final class JoinVerificationFlow {

    /** 验证流程状态。 */
    public enum State {
        /** 已申请（待开始验证）。 */
        APPLIED,
        /** 验证中。 */
        VERIFYING,
        /** 已通过（终态）。 */
        APPROVED,
        /** 已拒绝（终态）。 */
        REJECTED,
        /** 已超时（终态）。 */
        TIMED_OUT
    }

    private final Duration timeout;
    private final Instant appliedAt;
    private State state = State.APPLIED;

    /**
     * @param timeout   验证时限（正数）
     * @param appliedAt 申请提交时刻
     */
    public JoinVerificationFlow(Duration timeout, Instant appliedAt) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new TggException("入群验证：超时必须为正，实为 " + timeout);
        }
        if (appliedAt == null) {
            throw new TggException("入群验证：申请时刻不可为空");
        }
        this.timeout = timeout;
        this.appliedAt = appliedAt;
    }

    /** 当前状态（不触发超时判定）。 */
    public State currentState() {
        return state;
    }

    /** 开始验证：{@code APPLIED} → {@code VERIFYING}。 */
    public void startVerifying(Instant now) {
        require(State.APPLIED, "开始验证");
        state = State.VERIFYING;
    }

    /** 通过：{@code VERIFYING} → {@code APPROVED}。 */
    public void approve(Instant now) {
        require(State.VERIFYING, "通过");
        state = State.APPROVED;
    }

    /** 拒绝：{@code VERIFYING} → {@code REJECTED}。 */
    public void reject(Instant now) {
        require(State.VERIFYING, "拒绝");
        state = State.REJECTED;
    }

    /**
     * 触发超时判定并返回当前状态。
     *
     * @param now 当前时刻
     * @return 若处于非终态且已超时则返回 {@code TIMED_OUT}，否则返回当前状态
     */
    public State evaluate(Instant now) {
        if (now == null) {
            throw new TggException("入群验证：当前时刻不可为空");
        }
        if (!isTerminal(state) && !now.isBefore(appliedAt.plus(timeout))) {
            state = State.TIMED_OUT;
        }
        return state;
    }

    private static boolean isTerminal(State s) {
        return s == State.APPROVED || s == State.REJECTED || s == State.TIMED_OUT;
    }

    private void require(State expected, String action) {
        if (state != expected) {
            throw new TggException("入群验证：" + action + " 非法——当前 " + state + "，要求 " + expected);
        }
    }
}
