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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 异常基类契约测试（2026-10-03 补——此前 tgg-common 零测试）。
 *
 * <p>钉住的是<b>上层依赖的契约</b>：消息可读（fail-closed 文案的唯一载体）、
 * cause 链保留（诊断栈不丢根因）、可被 {@code RuntimeException}/{@code TggException}
 * 维度捕获（各模块 catch 一次的树根）。
 */
class TggExceptionTest {

    @Test
    @DisplayName("消息构造：getMessage 逐字保留")
    void messageIsPreserved() {
        assertThat(new TggException("守卫失败：状态不可迁移").getMessage())
                .isEqualTo("守卫失败：状态不可迁移");
    }

    @Test
    @DisplayName("cause 构造：链上根因可追溯")
    void causeChainIsPreserved() {
        IllegalStateException root = new IllegalStateException("root");
        TggException ex = new TggException("包装", root);
        assertThat(ex).hasCause(root);
    }

    @Test
    @DisplayName("继承面：是 RuntimeException——可被上层单点捕获")
    void isRuntimeException() {
        assertThat(new TggException("x")).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("fail-closed 语义：被抛出时按声明类型到达捕获点")
    void propagatesAsDeclared() {
        assertThatThrownBy(() -> {
            throw new TggException("fail-closed");
        }).isInstanceOf(TggException.class)
                .hasMessage("fail-closed");
    }
}
