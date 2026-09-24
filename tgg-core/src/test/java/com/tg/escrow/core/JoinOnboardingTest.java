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
import com.tg.escrow.core.JoinOnboarding.State;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 首次使用引导（GM-35）的行为固定测试。
 *
 * <p>四态：开始 → 引导中 → 完成 / 跳过。跳过是用户合法选择（随时可退出），
 * 完成与跳过均为终态——引导不得反复骚扰。
 */
class JoinOnboardingTest {

    private static final Instant T0 = Instant.parse("2026-09-23T00:00:00Z");

    private static JoinOnboarding fresh() {
        return new JoinOnboarding(555L, T0);
    }

    @Test
    @DisplayName("新建即 STARTED，并绑定用户")
    void startsWithUserBound() {
        JoinOnboarding o = fresh();

        assertThat(o.currentState()).isEqualTo(State.STARTED);
        assertThat(o.userId()).isEqualTo(555L);
    }

    @Test
    @DisplayName("正常路径：STARTED → GUIDING → COMPLETED")
    void happyPath() {
        JoinOnboarding o = fresh();
        o.startGuiding(T0);
        assertThat(o.currentState()).isEqualTo(State.GUIDING);

        o.complete(T0);
        assertThat(o.currentState()).isEqualTo(State.COMPLETED);
    }

    @Test
    @DisplayName("引导中可跳过：GUIDING → SKIPPED（用户随时可退出）")
    void canSkipWhileGuiding() {
        JoinOnboarding o = fresh();
        o.startGuiding(T0);

        o.skip(T0);
        assertThat(o.currentState()).isEqualTo(State.SKIPPED);
    }

    @Test
    @DisplayName("STARTED 也可直接跳过")
    void canSkipImmediately() {
        JoinOnboarding o = fresh();

        o.skip(T0);
        assertThat(o.currentState()).isEqualTo(State.SKIPPED);
    }

    @Test
    @DisplayName("终态不可再迁移（引导不反复骚扰）")
    void terminalIsFinal() {
        JoinOnboarding o = fresh();
        o.skip(T0);

        assertThatThrownBy(() -> o.complete(T0)).isInstanceOf(TggException.class);
        assertThatThrownBy(() -> o.startGuiding(T0)).isInstanceOf(TggException.class);
    }

    @Test
    @DisplayName("非法迁移：STARTED 直接 complete → 拒绝")
    void illegalTransitionRejected() {
        assertThatThrownBy(() -> fresh().complete(T0)).isInstanceOf(TggException.class);
    }
}
