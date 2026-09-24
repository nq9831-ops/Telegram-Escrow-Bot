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
import com.tg.escrow.core.SecondaryVerificationFlow.State;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 第二渠道验证（GM-26/T30 身份验证入口）的行为固定测试。
 *
 * <p>四态：已请求 → 验证中 → 通过/失败。终态不可迁移（验证结果不被事后改写）——
 * 与 {@code JoinVerificationFlow} 同族守卫。
 */
class SecondaryVerificationFlowTest {

    private static SecondaryVerificationFlow fresh() {
        return new SecondaryVerificationFlow(555L);
    }

    @Test
    @DisplayName("正向：REQUESTED → VERIFYING → VERIFIED")
    void happyPath() {
        SecondaryVerificationFlow f = fresh();
        assertThat(f.currentState()).isEqualTo(State.REQUESTED);

        f.startVerifying();
        assertThat(f.currentState()).isEqualTo(State.VERIFYING);

        f.pass();
        assertThat(f.currentState()).isEqualTo(State.VERIFIED);
    }

    @Test
    @DisplayName("验证失败：VERIFYING → FAILED")
    void fails() {
        SecondaryVerificationFlow f = fresh();
        f.startVerifying();

        f.fail();
        assertThat(f.currentState()).isEqualTo(State.FAILED);
    }

    @Test
    @DisplayName("非法迁移：REQUESTED 直接 pass → 拒绝")
    void illegalTransition() {
        assertThatThrownBy(() -> fresh().pass()).isInstanceOf(TggException.class);
    }

    @Test
    @DisplayName("终态不可迁移（结果不被事后改写）")
    void terminalIsFinal() {
        SecondaryVerificationFlow f = fresh();
        f.startVerifying();
        f.pass();

        assertThatThrownBy(f::fail).isInstanceOf(TggException.class);
        assertThat(f.currentState()).isEqualTo(State.VERIFIED);
    }

    @Test
    @DisplayName("绑定用户 ID")
    void bindsUser() {
        assertThat(fresh().userId()).isEqualTo(555L);
    }
}
