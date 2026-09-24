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
import com.tg.escrow.core.JoinVerificationFlow.State;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 入群验证流程（G12/G13）的行为固定测试。
 *
 * <p>四态生命周期：申请 → 验证中 → 通过/拒绝/超时。超时边界定死为<b>闭区间</b>
 * （{@code now >= appliedAt + timeout} 即超时），与项目内其它超时判定一致。
 * 终态不得被超时逻辑二次改写——已通过的申请不该因为时间流逝变成"超时"。
 */
class JoinVerificationFlowTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final Instant T0 = Instant.parse("2026-09-23T00:00:00Z");

    private static JoinVerificationFlow flow() {
        return new JoinVerificationFlow(TIMEOUT, T0);
    }

    @Test
    @DisplayName("初始状态 APPLIED（申请已提交）")
    void startsApplied() {
        assertThat(flow().currentState()).isEqualTo(State.APPLIED);
    }

    @Test
    @DisplayName("APPLIED → VERIFYING → APPROVED")
    void happyPathApprove() {
        JoinVerificationFlow f = flow();

        f.startVerifying(T0.plusSeconds(1));
        assertThat(f.currentState()).isEqualTo(State.VERIFYING);

        f.approve(T0.plusSeconds(2));
        assertThat(f.currentState()).isEqualTo(State.APPROVED);
    }

    @Test
    @DisplayName("APPLIED → VERIFYING → REJECTED")
    void happyPathReject() {
        JoinVerificationFlow f = flow();

        f.startVerifying(T0.plusSeconds(1));
        f.reject(T0.plusSeconds(2));
        assertThat(f.currentState()).isEqualTo(State.REJECTED);
    }

    @Test
    @DisplayName("APPLIED 状态超时 → TIMED_OUT")
    void timesOutFromApplied() {
        JoinVerificationFlow f = flow();

        assertThat(f.evaluate(T0.plusSeconds(61))).isEqualTo(State.TIMED_OUT);
    }

    @Test
    @DisplayName("VERIFYING 状态超时 → TIMED_OUT")
    void timesOutFromVerifying() {
        JoinVerificationFlow f = flow();
        f.startVerifying(T0.plusSeconds(1));

        assertThat(f.evaluate(T0.plusSeconds(61))).isEqualTo(State.TIMED_OUT);
    }

    @Test
    @DisplayName("未超时 evaluate 不变更状态")
    void notTimedOutKeepsState() {
        JoinVerificationFlow f = flow();

        assertThat(f.evaluate(T0.plusSeconds(30))).isEqualTo(State.APPLIED);
    }

    @Test
    @DisplayName("超时边界：恰好等于 timeout 即超时（闭区间）")
    void exactlyAtTimeout() {
        JoinVerificationFlow f = flow();

        assertThat(f.evaluate(T0.plusSeconds(60))).isEqualTo(State.TIMED_OUT);
    }

    @Test
    @DisplayName("终态 APPROVED 不被超时逻辑改写")
    void approvedIsTerminal() {
        JoinVerificationFlow f = flow();
        f.startVerifying(T0);
        f.approve(T0);

        assertThat(f.evaluate(T0.plusSeconds(999))).isEqualTo(State.APPROVED);
    }

    @Test
    @DisplayName("非法迁移：APPLIED 直接 approve → 拒绝")
    void cannotApproveFromApplied() {
        assertThatThrownBy(() -> flow().approve(T0)).isInstanceOf(TggException.class);
    }

    @Test
    @DisplayName("非法迁移：终态 APPROVED 再 reject → 拒绝")
    void cannotRejectFromTerminal() {
        JoinVerificationFlow f = flow();
        f.startVerifying(T0);
        f.approve(T0);

        assertThatThrownBy(() -> f.reject(T0)).isInstanceOf(TggException.class);
        assertThat(f.currentState()).isEqualTo(State.APPROVED);
    }

    @Test
    @DisplayName("非正 timeout / appliedAt 为空 → 构造期拒绝")
    void invalidConfigRejected() {
        assertThatThrownBy(() -> new JoinVerificationFlow(Duration.ZERO, T0))
                .isInstanceOf(TggException.class);
        assertThatThrownBy(() -> new JoinVerificationFlow(TIMEOUT, null))
                .isInstanceOf(TggException.class);
    }
}
