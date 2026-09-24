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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 争议双方陈述（ET-60：双方完整陈述 + 确认已读）的行为固定测试。
 *
 * <p>三条语义：①仅买卖双方可陈述、各一条（完整陈述后不再刷屏）；②<b>对方确认已读</b>——
 * 裁决前双方都看过对方陈述；③双方陈述齐 + 双方已读 → 陈述阶段完成（交裁决）。
 */
class DisputeStatementFlowTest {

    private static final long BUYER = 1001L;
    private static final long SELLER = 2002L;

    private static DisputeStatementFlow flow() {
        return new DisputeStatementFlow(BUYER, SELLER);
    }

    @Test
    @DisplayName("双方各提交一条陈述")
    void bothSubmit() {
        DisputeStatementFlow f = flow();

        f.submit(BUYER, "我付了款但没收到货");
        f.submit(SELLER, "货已发出，物流单号 XYZ");

        assertThat(f.statementOf(BUYER)).hasValue("我付了款但没收到货");
        assertThat(f.statementOf(SELLER)).hasValue("货已发出，物流单号 XYZ");
    }

    @Test
    @DisplayName("同一方只能陈述一次（完整陈述，不刷屏）")
    void oneStatementEach() {
        DisputeStatementFlow f = flow();
        f.submit(BUYER, "第一条");

        assertThatThrownBy(() -> f.submit(BUYER, "第二条")).isInstanceOf(EscrowException.class);
    }

    @Test
    @DisplayName("非当事方不得陈述")
    void outsiderCannotSubmit() {
        assertThatThrownBy(() -> flow().submit(9L, "路过"))
                .isInstanceOf(EscrowException.class);
    }

    @Test
    @DisplayName("对方确认已读：读对方的陈述合法；读自己的拒绝")
    void readReceipts() {
        DisputeStatementFlow f = flow();
        f.submit(BUYER, "买方陈述");
        f.submit(SELLER, "卖方陈述");

        f.markRead(SELLER, BUYER); // 卖方已读买方
        f.markRead(BUYER, SELLER); // 买方已读卖方

        assertThat(f.complete()).isTrue();
        assertThatThrownBy(() -> f.markRead(BUYER, BUYER)).isInstanceOf(EscrowException.class);
    }

    @Test
    @DisplayName("陈述未齐/未互相已读 → 未完成（不交裁决）")
    void incompleteWithoutBoth() {
        DisputeStatementFlow f = flow();
        f.submit(BUYER, "买方陈述");

        assertThat(f.complete()).isFalse();
    }
}
