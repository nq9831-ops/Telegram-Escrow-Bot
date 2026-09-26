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

import com.tg.escrow.escrow.EscrowOrder.State;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 交易状态视图（T2）的行为固定测试。
 *
 * <p>视图面向用户，所以每一条断言都在钉两件事：<b>每个状态都有可读文案</b>（不出空白），
 * 以及<b>文案只讲状态语义、不含订单主体字段</b>（避免把用户 ID 等带进面向群的输出）。
 */
class TradeStatusViewTest {

    @Test
    @DisplayName("每个状态都有非空的摘要与下一步（用户不会看到空白）")
    void everyStateHasSummaryAndNextStep() {
        for (State state : State.values()) {
            TradeStatusView view = TradeStatusView.of(state);

            assertThat(view.state()).isEqualTo(state);
            assertThat(view.summary()).as("状态 %s 摘要", state).isNotBlank();
            assertThat(view.nextStep()).as("状态 %s 下一步", state).isNotBlank();
        }
    }

    @Test
    @DisplayName("放行中的状态，下一步指向仍可操作的一方")
    void activeStatesPointToNextActor() {
        // OPEN 由命令层两步流产生（买方预览风险 → 确认落单），已实现的下一条路径是**买方直接托管**
        // （EscrowOrder.markLocked 允许 OPEN→LOCKED；EscrowTradeService.lock 仅买方可用）。
        // 「卖方确认接单」这一节点在命令层不存在——文案不得指向它，否则会指挥用户去做做不到的事。
        assertThat(TradeStatusView.of(State.OPEN).nextStep()).contains("买方");
        assertThat(TradeStatusView.of(State.CONFIRMED).nextStep()).contains("买方");
        assertThat(TradeStatusView.of(State.LOCKED).nextStep()).contains("卖方");
        assertThat(TradeStatusView.of(State.DELIVERED).nextStep()).contains("买方");
    }

    @Test
    @DisplayName("争议状态指向裁决方")
    void disputedPointsToAdjudicator() {
        assertThat(TradeStatusView.of(State.DISPUTED).nextStep()).contains("裁决");
    }

    @Test
    @DisplayName("终态表示流程已结束，无后续操作方")
    void terminalStatesHaveNoFurtherActor() {
        assertThat(TradeStatusView.of(State.RELEASED).nextStep()).contains("无");
        assertThat(TradeStatusView.of(State.REFUNDED).nextStep()).contains("无");
        assertThat(TradeStatusView.of(State.CANCELLED).nextStep()).contains("无");
    }
}
