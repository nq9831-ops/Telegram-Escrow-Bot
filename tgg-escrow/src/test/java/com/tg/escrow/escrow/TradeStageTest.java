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
 * 交易阶段标注（T38）的行为固定测试。
 *
 * <p>阶段是给用户看的粗粒度视图。关键约束是<b>全覆盖</b>：8 个状态每一个都必须
 * 落到某个阶段，不允许出现"某个状态下界面显示空白"。所以本测试遍历所有状态。
 */
class TradeStageTest {

    @Test
    @DisplayName("8 个状态全部映射到非空阶段（不允许状态漏映射）")
    void everyStateMapsToStage() {
        for (State state : State.values()) {
            assertThat(TradeStage.of(state))
                    .as("状态 %s 应有对应阶段", state)
                    .isNotNull();
        }
    }

    @Test
    @DisplayName("具体映射：下单→履约→确认→维护/结算，争议独立，取消独立")
    void specificMappings() {
        assertThat(TradeStage.of(State.OPEN)).isEqualTo(TradeStage.ORDERING);
        assertThat(TradeStage.of(State.CONFIRMED)).isEqualTo(TradeStage.ORDERING);
        assertThat(TradeStage.of(State.LOCKED)).isEqualTo(TradeStage.FULFILLING);
        assertThat(TradeStage.of(State.DELIVERED)).isEqualTo(TradeStage.CONFIRMING);
        assertThat(TradeStage.of(State.RELEASED)).isEqualTo(TradeStage.MAINTENANCE);
        assertThat(TradeStage.of(State.DISPUTED)).isEqualTo(TradeStage.DISPUTED);
        assertThat(TradeStage.of(State.REFUNDED)).isEqualTo(TradeStage.SETTLEMENT);
        assertThat(TradeStage.of(State.CANCELLED)).isEqualTo(TradeStage.CANCELLED);
    }
}
