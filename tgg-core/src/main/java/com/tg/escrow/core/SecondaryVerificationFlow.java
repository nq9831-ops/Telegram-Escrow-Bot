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

/**
 * 第二渠道验证（GM-26/T30 身份验证入口）：已请求 → 验证中 → 通过/失败。
 *
 * <p>守卫与 {@link JoinVerificationFlow} 同族：非法迁移抛、<b>终态不被事后改写</b>（
 * 验证结果一旦落定就不翻案——否则"已通过"的用户会被一次迟到的 fail 打回）。
 * 有状态、非线程安全。
 */
public final class SecondaryVerificationFlow {

    /** 验证状态。 */
    public enum State {
        /** 已请求（入口已发起）。 */
        REQUESTED,
        /** 验证中。 */
        VERIFYING,
        /** 已通过（终态）。 */
        VERIFIED,
        /** 已失败（终态）。 */
        FAILED
    }

    private final long userId;
    private State state = State.REQUESTED;

    /**
     * @param userId 待验证用户
     */
    public SecondaryVerificationFlow(long userId) {
        this.userId = userId;
    }

    public long userId() {
        return userId;
    }

    public State currentState() {
        return state;
    }

    /** 开始验证：{@code REQUESTED} → {@code VERIFYING}。 */
    public void startVerifying() {
        require(State.VERIFYING, "开始验证", State.REQUESTED);
    }

    /** 验证通过：{@code VERIFYING} → {@code VERIFIED}。 */
    public void pass() {
        require(State.VERIFIED, "验证通过", State.VERIFYING);
    }

    /** 验证失败：{@code VERIFYING} → {@code FAILED}。 */
    public void fail() {
        require(State.FAILED, "验证失败", State.VERIFYING);
    }

    private void require(State target, String action, State expected) {
        if (state != expected) {
            throw new TggException("第二渠道验证：" + action + " 非法——当前 " + state
                    + "，要求 " + expected + "（目标 " + target + "）");
        }
        state = target;
    }
}
