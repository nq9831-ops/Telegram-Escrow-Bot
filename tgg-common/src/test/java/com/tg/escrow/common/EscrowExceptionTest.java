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
package com.tg.escrow.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 担保领域异常契约测试（2026-10-03 补）。
 *
 * <p>关键契约：{@code EscrowException} 是 {@link TggException} 的<b>子类</b>——
 * 上层既能按 {@code TggException} 宽捕（本系统全部业务失败）、
 * 也能按 {@code EscrowException} 窄捕（仅担保领域失败）单独立账。
 */
class EscrowExceptionTest {

    @Test
    @DisplayName("继承面：是 TggException 子类——宽捕/窄捕两级都成立")
    void isSubtypeOfTggException() {
        assertThat(new EscrowException("x")).isInstanceOf(TggException.class);
    }

    @Test
    @DisplayName("消息与 cause 逐字/逐链保留")
    void messageAndCausePreserved() {
        IllegalStateException root = new IllegalStateException("db");
        EscrowException ex = new EscrowException("订单状态迁移非法", root);
        assertThat(ex.getMessage()).isEqualTo("订单状态迁移非法");
        assertThat(ex).hasCause(root);
    }
}
