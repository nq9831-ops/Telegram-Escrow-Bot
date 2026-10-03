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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 互刷模式检测（ET-65：A 和 B 反复交易 → 标可疑）的行为固定测试。
 *
 * <p>与 {@code CounterpartyDiversityScorer} 的区别：那是<b>单向</b>占比（一方过度依赖单一对手）；
 * 本类判<b>双向互指</b>——A 的交易集中于 B、且 B 的交易集中于 A，才是互刷形态。
 * 两侧都严格超过阈值才可疑（单向倾斜不误伤）。
 */
class MutualBrushDetectorTest {

    private static final BigDecimal THRESHOLD = new BigDecimal("0.6");

    @Test
    @DisplayName("双向互指（A 集中于 B 且 B 集中于 A）→ SUSPICIOUS")
    void mutualConcentrationSuspicious() {
        Map<Long, Integer> aCounts = Map.of(2L, 9, 3L, 1); // A 的 90% 给了 B
        Map<Long, Integer> bCounts = Map.of(1L, 9, 3L, 1); // B 的 90% 给了 A

        assertThat(MutualBrushDetector.isSuspicious(1L, aCounts, 2L, bCounts, THRESHOLD))
                .isTrue();
    }

    @Test
    @DisplayName("单向倾斜（A 集中于 B，但 B 交易分散）→ NORMAL（不误伤大客户关系）")
    void oneSidedNormal() {
        Map<Long, Integer> aCounts = Map.of(2L, 9, 3L, 1);
        Map<Long, Integer> bCounts = Map.of(1L, 2, 3L, 8);

        assertThat(MutualBrushDetector.isSuspicious(1L, aCounts, 2L, bCounts, THRESHOLD))
                .isFalse();
    }

    @Test
    @DisplayName("双方互指但恰在阈值（60%）→ NORMAL（严格超过才算）")
    void atThresholdNormal() {
        Map<Long, Integer> aCounts = Map.of(2L, 3, 3L, 2); // 60%
        Map<Long, Integer> bCounts = Map.of(1L, 3, 3L, 2); // 60%

        assertThat(MutualBrushDetector.isSuspicious(1L, aCounts, 2L, bCounts, THRESHOLD))
                .isFalse();
    }

    @Test
    @DisplayName("null 分布 / 阈值越界 → fail-closed")
    void invalidInputs() {
        assertThatThrownBy(() -> MutualBrushDetector.isSuspicious(1L, null, 2L, Map.of(), THRESHOLD))
                .isInstanceOf(EscrowException.class);
        assertThatThrownBy(() -> MutualBrushDetector.isSuspicious(1L, Map.of(), 2L, Map.of(),
                new BigDecimal("1.5"))).isInstanceOf(EscrowException.class);
    }
}
