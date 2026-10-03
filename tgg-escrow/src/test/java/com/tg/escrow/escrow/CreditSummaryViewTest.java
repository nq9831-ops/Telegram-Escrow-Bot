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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code /escrow credit} 的文案契约（Wave 3）。
 *
 * <p>本类钉的是<b>用户实际看到什么</b>，而不是分数公式（公式在 {@code CreditScore}、
 * 口径在 {@code TraderCreditServiceTest}）。三条要点：
 * <ol>
 *   <li><b>不能只报分数</b>——零历史用户也会拿到 40 分（两个反向维度在"数据为零"时给满分），
 *       只报数字等于让新人看起来"白得 40 分"。故必须同时给出笔数与各维度。</li>
 *   <li><b>无评价要如实标注</b>——{@code reviewCount == 0} 时明说"暂无评价"，
 *       而不是让用户以为 0% 好评率是别人给了他差评。</li>
 *   <li><b>零历史要给出来源解释</b>——直接说明这 40 分来自哪两个维度，不让人误读为实际信用。</li>
 * </ol>
 */
class CreditSummaryViewTest {

    private static final long USER = 1001L;
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Map<Long, Integer> SPREAD = Map.of(1L, 1, 2L, 1, 3L, 1, 4L, 1, 5L, 1);

    private final TraderCreditService service = new TraderCreditService();

    @Test
    @DisplayName("互刷剔除造成的 0 笔 ≠ 零历史：不得同时说「尚无成交记录」（两句话自相矛盾）")
    void excludedToZeroIsNotZeroHistory() {
        TraderCredit credit = new TraderCredit(USER, 41, TraderTier.Tier.NEWBIE, 0, 0,
                new BigDecimal("0"));
        CreditBreakdown breakdown = new CreditBreakdown(0, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, 44, new BigDecimal("0.729"));

        String out = CreditSummaryView.render(credit, breakdown, 6);

        assertThat(out).as("剔除造成的 0 笔要能解释来源").contains("未计入");
        assertThat(out).as("用户有 6 笔交易，说「尚无成交记录」是假话")
                .doesNotContain("尚无成交记录");
    }

    @Test
    @DisplayName("互刷剔除要披露：被剔除笔数 > 0 时文案说明来源与「不代表违规认定」")
    void disclosesBrushExclusion() {
        TraderCredit credit = new TraderCredit(USER, 60, TraderTier.Tier.BRONZE, 8, 3,
                new BigDecimal("0.2"));
        CreditBreakdown breakdown = new CreditBreakdown(8, BigDecimal.ZERO, BigDecimal.ONE,
                new BigDecimal("0.2"), 30);

        String out = CreditSummaryView.render(credit, breakdown, 8);

        assertThat(out).as("用户会拿它和记忆里的笔数对账——不说就以为数据错了")
                .contains("8 笔").contains("未计入").contains("不代表违规认定");
    }

    @Test
    @DisplayName("无剔除时不得出现任何互刷字样（不能吓唬正常用户）")
    void silentWhenNothingExcluded() {
        TraderCredit credit = new TraderCredit(USER, 60, TraderTier.Tier.BRONZE, 8, 3,
                new BigDecimal("0.2"));
        CreditBreakdown breakdown = new CreditBreakdown(8, BigDecimal.ZERO, BigDecimal.ONE,
                new BigDecimal("0.2"), 30);

        assertThat(CreditSummaryView.render(credit, breakdown, 0)).doesNotContain("互刷");
    }

    private static TraderStats stats(int completed, int totalCount, int positiveReviews,
                                     int reviewCount, Instant firstAt, Instant lastAt,
                                     Map<Long, Integer> counterparties) {
        return new TraderStats(USER, completed, 0, totalCount, positiveReviews, reviewCount,
                firstAt, lastAt, counterparties);
    }

    private String render(TraderStats stats) {
        return CreditSummaryView.render(service.summarize(stats), service.breakdownOf(stats));
    }

    @Test
    @DisplayName("老手：评分、等级徽标 + 中文名、笔数与**五个维度**一个不少（不能只报分数）")
    void activeTraderSeesScoreTierAndEveryDimension() {
        TraderStats veteran = stats(35, 35, 10, 10, T0, T0.plusSeconds(364L * 86400), SPREAD);

        String text = render(veteran);

        assertThat(text)
                .contains("信用摘要")
                .contains("88/100")
                .contains("🥇").contains("金牌")
                .contains("完成交易 35 笔").contains("收到评价 10 条")
                .contains("完成笔数：35/50")
                .contains("争议率：0%")
                .contains("好评率：100%")
                .contains("对手集中度：20%")
                .contains("活跃时长：365/365 天");
        assertThat(text)
                .as("有评价就不该出现「暂无评价」，零历史也不该出现来源解释")
                .doesNotContain("暂无评价")
                .doesNotContain("尚无成交记录");
    }

    @Test
    @DisplayName("无评价：明示「暂无评价」并说明该维度按 0 计——不让人误以为被给了差评")
    void noReviewsIsLabelledNotScoredAsBad() {
        TraderStats noReviews = stats(5, 5, 0, 0, T0, T0, Map.of(2002L, 5));

        String text = render(noReviews);

        assertThat(text).contains("暂无评价").contains("好评率：0%");
        assertThat(text)
                .as("有成交史就不该套用「尚无成交记录」那段解释")
                .doesNotContain("尚无成交记录");
    }

    @Test
    @DisplayName("零历史：如实说明 40 分的来源（反向维度的计分），并给出 0 笔")
    void newUserSeesWhyTheyHaveFortyPoints() {
        TraderStats fresh = stats(0, 0, 0, 0, null, null, Map.of());

        String text = render(fresh);

        assertThat(text)
                .contains("40/100")
                .contains("🌱").contains("新手")
                .contains("完成交易 0 笔")
                .contains("尚无成交记录");
        assertThat(text)
                .as("解释里必须点明分数从哪来，否则用户无从知道这 40 分是什么")
                .contains("反向维度").contains("不代表实际信用");
    }

    @Test
    @DisplayName("【线上撞到的边界】有订单但一笔未完成 → 不得声称「两个反向维度都满分」")
    void partialHistoryDoesNotClaimBothReverseDimensions() {
        // 1 个对手占 100%（对手集中度维度实际 0 分）、活跃 1 天、0 完成 0 评价
        // → 手算 0.25（无争议）+ 0.0003（活跃）≈ 25 分：只可能来自「无争议」一项
        TraderStats partial = stats(0, 1, 0, 0, T0, T0, Map.of(2002L, 1));

        String text = render(partial);

        assertThat(text).contains("25/100").contains("尚无成交记录");
        assertThat(text).contains("对手集中度：100%");
        assertThat(text)
                .as("该用户对手集中度是 100%（此项 0 分），说「两个反向维度的满分」与数据不符")
                .doesNotContain("两个反向维度的满分")
                .doesNotContain("无对手集中");
    }

    @Test
    @DisplayName("【审查 F2】活跃期很长 → 活跃时长是正向维度，不得说分数「只来自反向维度」")
    void longActiveSpanIsNotClaimedAsReverseOnly() {
        // 0 完成、0 评价，但跨度 200 天 → 活跃维度贡献 200/365×0.10 ≈ 5 分（正向维度真金白银）
        TraderStats veteran = stats(0, 0, 0, 0, T0, T0.plusSeconds(199L * 86400), Map.of());

        String text = render(veteran);

        assertThat(text).contains("尚无成交记录");
        assertThat(text)
                .as("活跃时长与完成笔数同属正向维度，它贡献的分不是「反向维度」给的")
                .doesNotContain("只来自反向维度")
                .doesNotContain("全部来自");
    }

    @Test
    @DisplayName("入参缺失 → 构造期即抛（宁可失败也不渲染半截文案）")
    void missingArgumentsFailClosed() {
        TraderCredit credit = service.summarize(stats(5, 5, 0, 0, T0, T0, SPREAD));
        CreditBreakdown breakdown = service.breakdownOf(stats(5, 5, 0, 0, T0, T0, SPREAD));

        assertThatThrownBy(() -> CreditSummaryView.render(null, breakdown))
                .isInstanceOf(EscrowException.class);
        assertThatThrownBy(() -> CreditSummaryView.render(credit, null))
                .isInstanceOf(EscrowException.class);
    }
}
