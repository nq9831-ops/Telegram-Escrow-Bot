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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 互刷不累加（④ 差距 3，ET-38/T53）：可疑对手对的交易不计入信用分。
 *
 * <h2>判据与评估链同源，防止两处口径漂移</h2>
 * <p>「可疑」由 {@link MutualBrushDetector#isSuspicious} 判定（<b>双向</b>集中度都超阈值），
 * 阈值与 `tgg.fraud.concentration-threshold` 同一来源；新手豁免复用 {@link NewcomerBadge}。
 *
 * <h2>三条不可越界的边界</h2>
 * <ul>
 *   <li><b>单向集中不剔除</b>：大客户关系天然单向集中，误伤会把正常生意打成互刷；</li>
 *   <li><b>新手豁免</b>：前 3 笔不启用剔除（与评估链同一豁免阈值）；</li>
 *   <li><b>对手数据不可得时不判</b>：双向判据缺一半就无从成立，宁可放过也不误伤。</li>
 * </ul>
 */
class TraderBrushExclusionTest {

    private static final long USER_A = 1L;
    private static final long USER_B = 2L;
    private static final long OTHER = 3L;
    private static final BigDecimal THRESHOLD = new BigDecimal("0.6");
    private static final Instant T0 = Instant.parse("2026-09-01T00:00:00Z");

    @Test
    @DisplayName("双向集中超阈值 → 该对手的笔数从有效统计中剔除，且不再计入对手分布")
    void mutualPairIsExcluded() {
        // A：与 B 8 笔、与 OTHER 2 笔（B 占 0.8）；B：与 A 8 笔、与 4 号 2 笔（A 占 0.8）→ 双向超阈值
        TraderStats a = stats(USER_A, 10, 10, Map.of(USER_B, 8, OTHER, 2));
        TraderStats b = stats(USER_B, 10, 10, Map.of(USER_A, 8, 4L, 2));

        TraderCreditService.BrushAdjustment adjusted =
                new TraderCreditService(CreditScore.defaults(), THRESHOLD)
                        .adjustForBrush(a, id -> id == USER_B ? b : null);

        assertThat(adjusted.excludedTrades()).as("被剔除的笔数要如实报出，供渲染层披露").isEqualTo(8);
        assertThat(adjusted.effective().completed()).isEqualTo(2);
        assertThat(adjusted.effective().totalCount()).isEqualTo(2);
        assertThat(adjusted.effective().counterpartyCounts())
                .as("剔除后对手分布里不应再有该对手——否则对手集中度仍被它污染")
                .containsOnlyKeys(OTHER);
    }

    @Test
    @DisplayName("单向集中（大客户）→ 不剔除：误伤会把正常生意打成互刷")
    void oneWayConcentrationIsNotExcluded() {
        TraderStats a = stats(USER_A, 10, 10, Map.of(USER_B, 8, OTHER, 2));
        // B 那边 A 只占 0.1——A 集中但 B 不集中，不是互刷形态
        TraderStats b = stats(USER_B, 10, 10, Map.of(USER_A, 1, 4L, 9));

        TraderCreditService.BrushAdjustment adjusted =
                new TraderCreditService(CreditScore.defaults(), THRESHOLD)
                        .adjustForBrush(a, id -> id == USER_B ? b : null);

        assertThat(adjusted.excludedTrades()).isZero();
        assertThat(adjusted.effective()).isSameAs(a);
    }

    @Test
    @DisplayName("新手豁免：前 3 笔不启用剔除（与评估链 NewcomerBadge 同源阈值）")
    void newcomerIsExempt() {
        TraderStats a = stats(USER_A, 2, 2, Map.of(USER_B, 2));
        TraderStats b = stats(USER_B, 2, 2, Map.of(USER_A, 2));

        TraderCreditService.BrushAdjustment adjusted =
                new TraderCreditService(CreditScore.defaults(), THRESHOLD)
                        .adjustForBrush(a, id -> id == USER_B ? b : null);

        assertThat(adjusted.excludedTrades())
                .as("新手期样本太少，判定不可靠——豁免的是检测生效时点，不是允许互刷")
                .isZero();
    }

    @Test
    @DisplayName("对手统计不可得 → 不判可疑（双向判据缺一半，宁可放过）")
    void missingCounterpartyStatsIsNotSuspicious() {
        TraderStats a = stats(USER_A, 10, 10, Map.of(USER_B, 9));

        TraderCreditService.BrushAdjustment adjusted =
                new TraderCreditService(CreditScore.defaults(), THRESHOLD)
                        .adjustForBrush(a, id -> null);

        assertThat(adjusted.excludedTrades()).isZero();
    }

    @Test
    @DisplayName("剔除后参与评分：刷出来的完成数与对手集中度都不再计分")
    void excludedTradesDoNotCountTowardScore() {
        TraderStats a = stats(USER_A, 10, 10, Map.of(USER_B, 8, OTHER, 2));
        TraderStats b = stats(USER_B, 10, 10, Map.of(USER_A, 8, 4L, 2));
        TraderCreditService service = new TraderCreditService(CreditScore.defaults(), THRESHOLD);

        TraderCreditService.BrushAdjustment adjusted = service.adjustForBrush(a, id -> id == USER_B ? b : null);

        assertThat(adjusted.effective().completed()).isLessThan(a.completed());
        assertThat(service.scoreOf(adjusted.effective()))
                .as("互刷不可抬高评分——这是 ET-38 的全部意义")
                .isLessThan(service.scoreOf(a));
    }

    private static TraderStats stats(long userId, int completed, int total, Map<Long, Integer> counts) {
        return new TraderStats(userId, completed, 0, total, 0, 0, T0, T0, new HashMap<>(counts));
    }
}
