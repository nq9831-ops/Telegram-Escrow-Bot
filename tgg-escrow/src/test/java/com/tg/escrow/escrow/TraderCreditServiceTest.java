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
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 交易者信用读模型的行为固定测试（ET-03/37/75/76/72 的接线侧）。
 *
 * <p>本类钉住的是**口径**而不是公式——公式在 {@link CreditScore} 里早已被固化。
 * 口径一旦含糊，日后就会出现两套算法并存（例如"完成"含不含退款、争议率的分母是谁），
 * 而同一个人在两套口径下得到不同等级。故每条口径都用**可观察的分数/等级差异**钉住。
 *
 * <p>所有期望分数都是按 {@link CreditScore} 的权重手算得出的定点值，不是"跑出来多少写多少"。
 */
class TraderCreditServiceTest {

    private static final long USER = 1001L;
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private final TraderCreditService service = new TraderCreditService();

    private static TraderStats stats(int completed, int disputeCount, int totalCount,
                                     int positiveReviews, int reviewCount,
                                     Instant firstAt, Instant lastAt,
                                     Map<Long, Integer> counterparties) {
        return new TraderStats(USER, completed, disputeCount, totalCount,
                positiveReviews, reviewCount, firstAt, lastAt, counterparties);
    }

    // ── 口径 1：空历史不得抛异常，且等级为新手 ─────────────────────────────

    @Test
    @DisplayName("口径【空历史】无任何交易 → 不抛异常，评分 40，等级 NEWBIE（<5 笔一律新手）")
    void emptyHistoryScoresWithoutThrowing() {
        TraderStats empty = stats(0, 0, 0, 0, 0, null, null, Map.of());

        assertThat(service.scoreOf(empty)).isEqualTo(40);
        assertThat(service.tierOf(empty)).isEqualTo(TraderTier.Tier.NEWBIE);
    }

    // ── 口径 3：无评价 → 该维度取 0（不是满分）─────────────────────────────

    @Test
    @DisplayName("口径【无评价→0】同样 35 笔，有 10 条全好评比无评价高 20 分")
    void noReviewsContributesZeroNotFullMarks() {
        Map<Long, Integer> spread = Map.of(1L, 1, 2L, 1, 3L, 1, 4L, 1, 5L, 1);

        TraderStats noReviews = stats(35, 0, 35, 0, 0, T0, T0.plusSeconds(364L * 86400), spread);
        TraderStats allPositive = stats(35, 0, 35, 10, 10, T0, T0.plusSeconds(364L * 86400), spread);

        // 无评价：0.21（完成）+ 0.25（无争议）+ 0（好评维度）+ 0.12（分散）+ 0.10（满年）= 0.68 → 68
        assertThat(service.scoreOf(noReviews)).isEqualTo(68);
        // 全好评：好评维度满额 0.20 → 0.88 → 88；两者只差这一维，正是"无评价不给满分"的直接证据
        assertThat(service.scoreOf(allPositive)).isEqualTo(88);
        assertThat(service.tierOf(allPositive)).isEqualTo(TraderTier.Tier.GOLD);
    }

    // ── 口径 5：多样性反向（对手越集中，分越低）───────────────────────────

    @Test
    @DisplayName("口径【多样性反向】同样 5 笔：全部同一个对手 28 分，分散到 5 个对手 40 分")
    void concentratedCounterpartyScoresLower() {
        TraderStats concentrated = stats(5, 0, 5, 0, 0, T0, T0, Map.of(2002L, 5));
        TraderStats spread = stats(5, 0, 5, 0, 0, T0, T0,
                Map.of(1L, 1, 2L, 1, 3L, 1, 4L, 1, 5L, 1));

        assertThat(service.scoreOf(concentrated)).isEqualTo(28);
        assertThat(service.scoreOf(spread)).isEqualTo(40);
    }

    // ── 口径 2：争议率的分母是「参与总笔数」（含非争议笔数）────────────────

    @Test
    @DisplayName("口径【争议率分母=参与总笔数】争议数相同、总笔数越大分越高")
    void disputeRateDenominatorIsTotalParticipation() {
        Map<Long, Integer> spread = Map.of(1L, 1, 2L, 1, 3L, 1, 4L, 1, 5L, 1);

        // 造数约束：completed ≤ totalCount（由 TraderStats 的不变量保证），
        // 故固定 completed=2、只让 totalCount 变化——这样分差只可能来自争议率的分母。
        TraderStats fewTotal = stats(2, 1, 2, 0, 0, T0, T0, spread);
        TraderStats manyTotal = stats(2, 1, 10, 0, 0, T0, T0, spread);

        // 2 笔里 1 笔争议 → 1 - 0.5 = 0.5 → 26 分；10 笔里 1 笔争议 → 1 - 0.1 = 0.9 → 36 分
        assertThat(service.scoreOf(fewTotal)).isEqualTo(26);
        assertThat(service.scoreOf(manyTotal)).isEqualTo(36);
        assertThat(service.scoreOf(manyTotal)).isGreaterThan(service.scoreOf(fewTotal));
    }

    // ── 口径 4：活跃天数 = 首末自然日跨度（含两端）─────────────────────────

    @Test
    @DisplayName("口径【活跃天数=首末跨度含两端】当天完成的交易记 1 天，满 365 天该维度封顶")
    void activeDaysSpansInclusive() {
        Map<Long, Integer> spread = Map.of(1L, 1);

        TraderStats sameDay = stats(5, 0, 5, 0, 0, T0, T0, spread);
        TraderStats fullYear = stats(5, 0, 5, 0, 0, T0, T0.plusSeconds(364L * 86400), spread);

        assertThat(service.activeDaysOf(sameDay)).isEqualTo(1);
        assertThat(service.activeDaysOf(fullYear)).isEqualTo(365);
        assertThat(service.scoreOf(fullYear)).isGreaterThan(service.scoreOf(sameDay));
    }

    // ── 等级门槛（笔数优先于评分）────────────────────────────────────────

    @Test
    @DisplayName("等级：35 笔且全好评且分散 → GOLD；50 笔同口径 → DIAMOND")
    void tiersFollowCompletedCountGate() {
        Map<Long, Integer> spread = Map.of(1L, 1, 2L, 1, 3L, 1, 4L, 1, 5L, 1);

        TraderStats gold = stats(35, 0, 35, 10, 10, T0, T0.plusSeconds(364L * 86400), spread);
        TraderStats diamond = stats(50, 0, 50, 10, 10, T0, T0.plusSeconds(364L * 86400), spread);

        assertThat(service.tierOf(gold)).isEqualTo(TraderTier.Tier.GOLD);
        assertThat(service.tierOf(diamond)).isEqualTo(TraderTier.Tier.DIAMOND);
    }

    // ── 汇总视图（Wave 3 的 /escrow credit 用它渲染）──────────────────────

    @Test
    @DisplayName("汇总视图带齐评分/等级/各维度，且如实反映「无评价」")
    void summaryCarriesBreakdown() {
        TraderStats noReviews = stats(5, 0, 5, 0, 0, T0, T0, Map.of(2002L, 5));

        TraderCredit credit = service.summarize(noReviews);

        assertThat(credit.userId()).isEqualTo(USER);
        assertThat(credit.score()).isEqualTo(28);
        assertThat(credit.tier()).isEqualTo(TraderTier.Tier.NEWBIE);
        assertThat(credit.completed()).isEqualTo(5);
        assertThat(credit.reviewCount()).isZero();
        assertThat(credit.diversityShare()).isEqualByComparingTo("1");
    }

    // ── 维度拆解（Wave 3 的 /escrow credit 文案输入）────────────────────────

    @Test
    @DisplayName("拆解：五维原始值一次给全，且与评分**同源**（同一条口径，不分叉）")
    void breakdownExposesEveryDimension() {
        TraderStats mixed = stats(10, 2, 20, 6, 10, T0, T0.plusSeconds(99L * 86400),
                Map.of(1L, 7, 2L, 3));

        CreditBreakdown bd = service.breakdownOf(mixed);

        assertThat(bd.completed()).isEqualTo(10);
        assertThat(bd.disputeRate()).isEqualByComparingTo("0.1000");    // 2 争议 / 20 参与
        assertThat(bd.positiveRate()).isEqualByComparingTo("0.6000");   // 6 好评 / 10 评价
        assertThat(bd.diversityShare()).isEqualByComparingTo("0.7000"); // 7/10 同一对手
        assertThat(bd.activeDays()).isEqualTo(100);                     // 99 天跨度含两端

        assertThat(CreditScore.defaults().compute(bd.completed(), bd.disputeRate(), bd.positiveRate(),
                bd.diversityShare(), bd.activeDays()))
                .as("拆解必须就是评分用的那组值——否则文案解释的是另一套算法")
                .isEqualTo(service.scoreOf(mixed));
    }

    @Test
    @DisplayName("拆解：零历史不抛、五维全 0——40 分完全来自两个反向维度的满分")
    void breakdownOfEmptyHistoryIsAllZero() {
        CreditBreakdown bd = service.breakdownOf(stats(0, 0, 0, 0, 0, null, null, Map.of()));

        assertThat(bd.completed()).isZero();
        assertThat(bd.disputeRate()).isEqualByComparingTo("0");
        assertThat(bd.positiveRate()).isEqualByComparingTo("0");
        assertThat(bd.diversityShare()).isEqualByComparingTo("0");
        assertThat(bd.activeDays()).isZero();
        assertThat(CreditScore.defaults().compute(0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0))
                .isEqualTo(40);
    }

    // ── 入参格式即事实错误（与项目其它值对象一致：构造期拦）────────────────

    @Test
    @DisplayName("计数为负 / 好评数超过评价总数 / 只给了一端的时刻 → 构造期抛（不静默算出假分）")
    void malformedStatsFailFast() {
        assertThatThrownBy(() -> stats(-1, 0, 0, 0, 0, null, null, Map.of()))
                .isInstanceOf(EscrowException.class);
        assertThatThrownBy(() -> stats(0, 0, 0, 5, 3, null, null, Map.of()))
                .as("好评数不可能超过评价总数")
                .isInstanceOf(EscrowException.class);
        assertThatThrownBy(() -> stats(0, 0, 0, 0, 0, T0, null, Map.of()))
                .as("给了一端时刻就必须给另一端，否则活跃天数无法定义")
                .isInstanceOf(EscrowException.class);
    }

    // ── 榜单排序（Wave 2 的 /escrow rank 用）──────────────────────────────

    private static TraderStats statsOf(long id, int completed, int totalCount,
                                       int positiveReviews, int reviewCount,
                                       Map<Long, Integer> counterparties) {
        return new TraderStats(id, completed, 0, totalCount, positiveReviews, reviewCount,
                T0, T0.plusSeconds(364L * 86400), counterparties);
    }

    @Test
    @DisplayName("榜单：按评分降序、截断到 topN，且返回的是评分后的条目（不是原始统计）")
    void rankSortsByScoreDescendingAndTruncates() {
        Map<Long, Integer> spread = Map.of(1L, 1, 2L, 1, 3L, 1, 4L, 1, 5L, 1);
        TraderStats high = statsOf(9001L, 35, 35, 10, 10, spread);          // 88 分
        TraderStats mid = statsOf(9002L, 5, 5, 0, 0, spread);               // 40 分
        TraderStats low = statsOf(9003L, 5, 5, 0, 0, Map.of(2002L, 5));     // 28 分（对手集中）

        List<TraderCredit> top2 = service.rank(List.of(mid, low, high), 2);
        List<TraderCredit> all = service.rank(List.of(mid, low, high), 10);

        assertThat(top2).extracting(TraderCredit::userId).containsExactly(9001L, 9002L);
        // statsOf 固定了整年跨度（活跃维度满额 0.10），故这三档是 88 / 50 / 38
        assertThat(top2).extracting(TraderCredit::score).containsExactly(88, 50);
        assertThat(all).extracting(TraderCredit::userId).containsExactly(9001L, 9002L, 9003L);

        // 同分定序：两个统计完全相同（都是 28 分）→ 按 user ID 升序，且与传入顺序无关
        TraderStats tieHighId = statsOf(9010L, 5, 5, 0, 0, Map.of(2002L, 5));
        TraderStats tieLowId = statsOf(9009L, 5, 5, 0, 0, Map.of(2002L, 5));

        assertThat(service.rank(List.of(tieHighId, tieLowId), 5))
                .extracting(TraderCredit::userId).containsExactly(9009L, 9010L);
        assertThat(service.rank(List.of(tieLowId, tieHighId), 5))
                .as("传入顺序不该影响榜单顺序——公开榜单的顺序抖动会被读成排名变了")
                .extracting(TraderCredit::userId).containsExactly(9009L, 9010L);
    }

    @Test
    @DisplayName("榜单：空参与者 → 空列表（不是异常）；topN≤0 → 构造期即抛（不静默返回全榜）")
    void rankHandlesEmptyAndInvalidTopN() {
        assertThat(service.rank(List.of(), 10)).isEmpty();
        assertThat(service.rank(null, 10)).isEmpty();
        assertThatThrownBy(() -> service.rank(List.of(), 0))
                .isInstanceOf(EscrowException.class);
    }
}
