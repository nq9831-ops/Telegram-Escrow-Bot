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

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 仲裁员加入费动态门槛（ET-46）的行为固定测试。
 *
 * <p>语义：合格投票人越少，质押门槛越低（吸引更多人参与、避免投票人枯竭）。
 * 两条不变量：①<b>降低不破质押下限 1 USDT</b>（{@link VoteStake#MIN}）；
 * ②合格人数充足时门槛不打折。
 */
class StakeThresholdAdjusterTest {

    private static final BigDecimal BASE = new BigDecimal("4");

    @Test
    @DisplayName("合格投票人充足（≥ 预期的一半）→ 门槛不打折")
    void enoughVotersFullStake() {
        assertThat(StakeThresholdAdjuster.adjust(BASE, 10, 10))
                .isEqualByComparingTo("4");
    }

    @Test
    @DisplayName("合格投票人稀少（< 预期一半）→ 门槛减半")
    void scarceVotersHalved() {
        // 2 < 10 的一半 → 4 / 2 = 2
        assertThat(StakeThresholdAdjuster.adjust(BASE, 2, 10))
                .isEqualByComparingTo("2");
    }

    @Test
    @DisplayName("减半不破下限 1 USDT（VoteStake.MIN）")
    void floorAtOne() {
        // 1 < 4 的一半 → 减半为 0.5，但下限 1
        assertThat(StakeThresholdAdjuster.adjust(BigDecimal.ONE, 1, 4))
                .isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("恰好一半 → 不打折（\"低于一半\"不含本数）")
    void exactlyHalfNotDiscounted() {
        assertThat(StakeThresholdAdjuster.adjust(BASE, 5, 10))
                .isEqualByComparingTo("4");
    }

    @Test
    @DisplayName("负数人数 / 预期 ≤ 0 / 质押非正 → fail-closed")
    void invalidInputsRejected() {
        assertThatThrownBy(() -> StakeThresholdAdjuster.adjust(BASE, -1, 10))
                .isInstanceOf(EscrowException.class);
        assertThatThrownBy(() -> StakeThresholdAdjuster.adjust(BASE, 5, 0))
                .isInstanceOf(EscrowException.class);
        assertThatThrownBy(() -> StakeThresholdAdjuster.adjust(BigDecimal.ZERO, 5, 10))
                .isInstanceOf(EscrowException.class);
    }
}
