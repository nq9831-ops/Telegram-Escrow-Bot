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
package com.tg.escrow.chain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.ton.ton4j.tlb.VmStackValue;
import org.ton.ton4j.tlb.VmStackValueInt;
import org.ton.ton4j.tlb.VmStackValueTinyInt;

import java.math.BigInteger;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 链上状态查询的纯解析测试（不触网）——返回栈 → 状态值的全部边界。
 *
 * <p>单值返回无栈序问题；重点是<b>越界防御</b>（0-5 外的值视为解析错，绝不喂给对账）
 * 与两种整数变体都认（tiny/大整数）。
 */
class AdnlEscrowStateQueryTest {

    private static VmStackValue bigInt(long value) {
        return VmStackValueInt.builder().value(BigInteger.valueOf(value)).build();
    }

    private static VmStackValue tinyInt(long value) {
        return VmStackValueTinyInt.builder().value(BigInteger.valueOf(value)).build();
    }

    @Test
    @DisplayName("0-5 全谱解析：两种整数变体都认")
    void parsesAllStates() throws Exception {
        for (int state = 0; state <= 5; state++) {
            assertThat(AdnlEscrowStateQuery.stateFromValues(List.of(bigInt(state))))
                    .isEqualTo(state);
            assertThat(AdnlEscrowStateQuery.stateFromValues(List.of(tinyInt(state))))
                    .isEqualTo(state);
        }
    }

    @Test
    @DisplayName("空栈 / null 栈 → 抛（解析失败大声说出来）")
    void emptyStackRejected() {
        assertThatThrownBy(() -> AdnlEscrowStateQuery.stateFromValues(List.of()))
                .isInstanceOf(ChainUnavailableException.class);
        assertThatThrownBy(() -> AdnlEscrowStateQuery.stateFromValues(null))
                .isInstanceOf(ChainUnavailableException.class);
    }

    @Test
    @DisplayName("越界（6）→ 抛——未知状态绝不喂给对账")
    void outOfRangeRejected() {
        assertThatThrownBy(() -> AdnlEscrowStateQuery.stateFromValues(List.of(bigInt(6))))
                .isInstanceOf(ChainUnavailableException.class);
        assertThatThrownBy(() -> AdnlEscrowStateQuery.stateFromValues(List.of(bigInt(-1))))
                .isInstanceOf(ChainUnavailableException.class);
    }

    @Test
    @DisplayName("非整数（null 值）→ 抛")
    void nonIntegerRejected() {
        assertThatThrownBy(() -> AdnlEscrowStateQuery.stateFromValues(
                java.util.Collections.singletonList(null)))
                .isInstanceOf(ChainUnavailableException.class)
                .hasMessageContaining("null");
    }
}
