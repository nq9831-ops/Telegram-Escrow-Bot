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
 * 交易对手多样性评分（ET-72）的行为固定测试。
 *
 * <p>语义：「超过 60% 交易与同一人完成，标记可疑」。边界定死为<b>严格超过</b>
 * （恰 60% 不可疑）——「超过」不含本数。该指标同时是排名评分（ET-75）的多样性维度，
 * 一处定义、两处引用。
 */
class CounterpartyDiversityScorerTest {

    private static final BigDecimal THRESHOLD = new BigDecimal("0.6");

    private static boolean suspicious(Map<Long, Integer> counts) {
        return CounterpartyDiversityScorer.isSuspicious(counts, THRESHOLD);
    }

    @Test
    @DisplayName("单一对手占全部 → 可疑（share=1.0 > 0.6）")
    void singleCounterpartySuspicious() {
        assertThat(suspicious(Map.of(1L, 5))).isTrue();
    }

    @Test
    @DisplayName("恰 60% 不可疑——边界含不含本数在此定死（\"超过\"不含本数）")
    void exactlySixtyPercentNotSuspicious() {
        // 3 / 5 = 0.6 → 不可疑
        assertThat(suspicious(Map.of(1L, 3, 2L, 1, 3L, 1))).isFalse();
    }

    @Test
    @DisplayName("超过 60% 可疑（4/5 = 0.8）")
    void aboveSixtyPercentSuspicious() {
        assertThat(suspicious(Map.of(1L, 4, 2L, 1))).isTrue();
    }

    @Test
    @DisplayName("均衡分布（四人均摊）→ 不可疑")
    void evenDistributionNotSuspicious() {
        assertThat(suspicious(Map.of(1L, 2, 2L, 2, 3L, 2, 4L, 2))).isFalse();
    }

    @Test
    @DisplayName("空交易记录 → 占比 0，不可疑（无从判断，不臆断）")
    void emptyHistoryNotSuspicious() {
        assertThat(suspicious(Map.of())).isFalse();
        assertThat(CounterpartyDiversityScorer.maxShare(Map.of())).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("负计数 / null 映射 / 阈值越界 → fail-closed")
    void invalidInputsRejected() {
        assertThatThrownBy(() -> suspicious(Map.of(1L, -1))).isInstanceOf(EscrowException.class);
        assertThatThrownBy(() -> CounterpartyDiversityScorer.maxShare(null))
                .isInstanceOf(EscrowException.class);
        assertThatThrownBy(() -> CounterpartyDiversityScorer.isSuspicious(Map.of(1L, 1),
                new BigDecimal("1.5"))).isInstanceOf(EscrowException.class);
    }
}
