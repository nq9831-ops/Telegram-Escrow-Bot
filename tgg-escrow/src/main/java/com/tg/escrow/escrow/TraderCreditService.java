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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;

/**
 * 把 {@link TraderStats}（原始统计）变成评分、等级与信用摘要——<b>本类不含任何算术</b>。
 *
 * <h2>它做了什么、又不做什么</h2>
 * <p>算术在 {@link CreditScore}（五维加权）与 {@link TraderTier}（笔数门槛优先）里，
 * 多样性的定义在 {@link CounterpartyDiversityScorer} 里。<b>本类只负责把原始统计翻译成
 * 那三者要的入参</b>——也就是口径落地的地方。正因为口径只在这里出现一次，日后要改口径
 * 只需改这一处，而不是满仓找哪里还在算"完成率"。
 *
 * <h2>三条口径的落地细节（与 {@link TraderStats} 的注释成对）</h2>
 * <ul>
 *   <li><b>争议率分母为 0 时取 0</b>（而非"未定义"）：新用户没有争议也没有交易，
 *       此时"无争议"不该被当作满分以外的任何东西——分母为 0 的比值本就没有意义，
 *       取 0 让它在评分里贡献"无争议"那一侧的中性值。</li>
 *   <li><b>无评价取 0</b>（不是满分、也不是 0.5）："没被好评就不加分"。这不惩罚新人——
 *       {@link TraderTier} 里不满 5 笔一律 {@code NEWBIE}，新人本就不参与评级。</li>
 *   <li><b>活跃天数按自然日跨度含两端</b>：当天完成即 1 天。与 {@link CreditScore} 权重注释里
 *       的「活跃时长」用词一致，且只需首末两个时刻即可算出（无需按日去重）。</li>
 * </ul>
 *
 * <h2>一个必须说出口的性质（不是缺陷，但会误导）</h2>
 * <p>{@link CreditScore} 的两个<b>反向</b>维度（争议率、对手集中度）在"数据为零"时会给出
 * <b>满分</b>——因为"没有争议"和"没有集中"确实都是好事。于是<b>零历史用户也会拿到 40 分</b>。
 * 这是既有评分模型的设计（其 javadoc 明确写了"反向"），本波不改它；危害被
 * {@code TraderTier} 的笔数门槛挡住（不满 5 笔一律新手），但<b>面向用户展示分数时必须同时
 * 给出笔数</b>，否则"40 分"看起来像是白得的——{@link TraderCredit} 因此带上
 * {@code completed} 与 {@code reviewCount}，渲染方不得只报分数。
 *
 * <p>评分权重由注入的 {@link CreditScore} 实例决定（④ 差距 1 配置化）；无参构造保持既有口径。
 */
public final class TraderCreditService {

    /** 定点精度：与 {@link CounterpartyDiversityScorer} 内部口径一致。 */
    private static final int SCALE = 4;

    private final CreditScore score;

    /** 互刷剔除阈值——与 `tgg.fraud.concentration-threshold` 同源（两处阈值漂移比没有阈值更糟）。 */
    private final BigDecimal brushThreshold;

    /** 时间衰减策略（④ 差距 2）；{@code none()} = 不衰减（既有口径零漂移）。 */
    private final CreditDecay decay;

    /** 时钟——只为算"最近活跃距今"；不启用衰减时为 {@code null}（不读时间，保持确定性）。 */
    private final java.time.Clock clock;

    /** 既有口径（默认权重 + 默认阈值 + 不衰减）——既有调用方与测试零改动。 */
    public TraderCreditService() {
        this(CreditScore.defaults(), DEFAULT_BRUSH_THRESHOLD, CreditDecay.none(), null);
    }

    /** 权重来自配置的构造（④ 差距 1）：权重口径在装配期决定，构造期 fail-fast。 */
    public TraderCreditService(CreditScore score) {
        this(score, DEFAULT_BRUSH_THRESHOLD, CreditDecay.none(), null);
    }

    /**
     * @param score          评分器（权重口径）
     * @param brushThreshold 互刷剔除阈值（(0,1]；与评估链同一配置键，见 {@code tgg.fraud.concentration-threshold}）
     */
    public TraderCreditService(CreditScore score, BigDecimal brushThreshold) {
        this(score, brushThreshold, CreditDecay.none(), null);
    }

    /**
     * @param score          评分器（权重口径）
     * @param brushThreshold 互刷剔除阈值（(0,1]）
     * @param decay          时间衰减策略（不可为空；不衰减用 {@link CreditDecay#none()}）
     * @param clock          时钟（启用衰减时必须给；不衰减时可为 {@code null}）
     */
    public TraderCreditService(CreditScore score, BigDecimal brushThreshold,
                               CreditDecay decay, java.time.Clock clock) {
        if (score == null) {
            throw new com.tg.escrow.common.EscrowException("信用服务：未提供评分器");
        }
        if (brushThreshold == null || brushThreshold.signum() <= 0
                || brushThreshold.compareTo(BigDecimal.ONE) > 0) {
            throw new com.tg.escrow.common.EscrowException(
                    "信用服务：互刷阈值必须在 (0,1]，实为 " + brushThreshold);
        }
        if (decay == null) {
            throw new com.tg.escrow.common.EscrowException("信用服务：衰减策略不可为空（不衰减请用 CreditDecay.none()）");
        }
        this.score = score;
        this.brushThreshold = brushThreshold;
        this.decay = decay;
        this.clock = clock;
    }

    /** 默认互刷阈值——与 {@code tgg.fraud.concentration-threshold} 的默认值一致（0.6）。 */
    private static final BigDecimal DEFAULT_BRUSH_THRESHOLD = new BigDecimal("0.6");

    /**
     * 互刷剔除的结果。
     *
     * @param effective       剔除可疑对手交易后的有效统计（原 {@link TraderStats} 不被修改）
     * @param excludedTrades  被剔除的笔数（0 = 无可疑对）——渲染层须如实披露，静默改数会让人对不上账
     */
    public record BrushAdjustment(TraderStats effective, int excludedTrades) {
    }

    /**
     * 互刷不累加（④ 差距 3，ET-38/T53）：把与<b>可疑对手</b>之间的交易从有效统计中剔除。
     *
     * <h2>判据与评估链完全同源</h2>
     * <p>「可疑」由 {@link MutualBrushDetector#isSuspicious} 判定（双向集中度<b>都</b>超阈值），
     * 阈值来自同一配置键；新手豁免复用 {@link NewcomerBadge#isMutualBrushExempt}。
     * 两处各自实现判据会让"评估说可疑、评分照加"这种自相矛盾长期潜伏。
     *
     * <h2>三处刻意的保守</h2>
     * <ul>
     *   <li><b>单向集中不剔除</b>：大客户关系天然集中，误伤等于把正常生意打成互刷；</li>
     *   <li><b>对手统计不可得（lookup 返回 null）不判</b>：双向判据缺一半就无从成立；</li>
     *   <li><b>新手豁免</b>：样本太少时判定不可靠，前 3 笔不启用（与评估链同阈值）。</li>
     * </ul>
     *
     * <h2>已知的近似（不是精确扣减，如实标注）</h2>
     * <p>对手分布计的是<b>全部状态</b>的笔数，而「完成」只算 RELEASED——扣减时按对手笔数整体扣，
     * 并在扣到负数时夹到 0。精确扣减需要每对每状态一条查询，在榜单路径上是 O(N·M) 次查询，
     * 不值得；本波选「保守少扣 + 披露剔除笔数」，宁可少扣也不凭空多扣。
     *
     * @param stats  原始统计（不被修改）
     * @param lookup 对手统计查询；无数据返回 {@code null}
     */
    public BrushAdjustment adjustForBrush(TraderStats stats, java.util.function.LongFunction<TraderStats> lookup) {
        requireStats(stats);
        if (lookup == null) {
            throw new com.tg.escrow.common.EscrowException("信用服务：互刷剔除需要对手统计查询");
        }
        if (NewcomerBadge.isMutualBrushExempt(stats.completed()) || stats.counterpartyCounts().isEmpty()) {
            return new BrushAdjustment(stats, 0);
        }
        int excluded = 0;
        java.util.Map<Long, Integer> kept = new java.util.LinkedHashMap<>(stats.counterpartyCounts());
        for (java.util.Map.Entry<Long, Integer> entry : stats.counterpartyCounts().entrySet()) {
            TraderStats other = lookup.apply(entry.getKey());
            if (other == null) {
                continue;   // 双向判据缺一半：不判
            }
            if (MutualBrushDetector.isSuspicious(stats.userId(), stats.counterpartyCounts(),
                    entry.getKey(), other.counterpartyCounts(), brushThreshold)) {
                excluded += entry.getValue();
                kept.remove(entry.getKey());
            }
        }
        if (excluded == 0) {
            return new BrushAdjustment(stats, 0);
        }
        TraderStats effective = new TraderStats(stats.userId(),
                Math.max(stats.completed() - excluded, 0),
                stats.disputeCount(),
                Math.max(stats.totalCount() - excluded, 0),
                stats.positiveReviews(),
                stats.reviewCount(),
                stats.firstAt(),
                stats.lastAt(),
                kept);
        return new BrushAdjustment(effective, excluded);
    }

    /**
     * 活跃天数：首末自然日跨度，<b>含两端</b>；无历史（首末皆空）返回 0。
     */
    public int activeDaysOf(TraderStats stats) {
        requireStats(stats);
        if (stats.firstAt() == null) {
            return 0;
        }
        long days = ChronoUnit.DAYS.between(stats.firstAt(), stats.lastAt());
        return Math.toIntExact(days + 1);
    }

    /** 对手集中度：最大单一对手占比 [0,1]；无对手返回 0。 */
    public BigDecimal diversityShareOf(TraderStats stats) {
        requireStats(stats);
        return CounterpartyDiversityScorer.maxShare(stats.counterpartyCounts());
    }

    /**
     * 五维拆解——{@code /escrow credit} 用来向用户解释"分数从哪来"。
     *
     * <p>与 {@link #scoreOf} <b>同源</b>：评分就是把这五个值喂给 {@link CreditScore}，
     * 故文案里的拆解必然能加回总分，不会出现"文案解释的是 A 套口径、分数来自 B 套口径"。
     *
     * <p>轻量时间衰减（④ 差距 2）就落在这里：活跃天数按「最近活跃距今」折算，并把因子带进
     * {@link CreditBreakdown#decayFactor()} 供渲染层披露——分数与拆解仍可互加（衰减在维度内，
     * 不是对总分的事后乘除）。
     *
     * <p>返回的是原始值（比率给 BigDecimal，不给百分比字符串）——展示格式属渲染层。
     */
    public CreditBreakdown breakdownOf(TraderStats stats) {
        requireStats(stats);
        BigDecimal factor = decayFactorOf(stats);
        int rawActiveDays = activeDaysOf(stats);
        int effectiveActiveDays = BigDecimal.valueOf(rawActiveDays).multiply(factor)
                .setScale(0, java.math.RoundingMode.DOWN).intValue();
        return new CreditBreakdown(stats.completed(), disputeRateOf(stats), positiveRateOf(stats),
                diversityShareOf(stats), effectiveActiveDays, factor);
    }

    /** 时间衰减因子（未启用衰减或无活跃史时为 1）。 */
    private BigDecimal decayFactorOf(TraderStats stats) {
        if (decay == null || decay == CreditDecay.none()) {
            return BigDecimal.ONE;
        }
        return decay.factorFor(stats.lastAt(), clock == null ? null : clock.instant());
    }

    /** 综合评分（0–100）——即 {@link #breakdownOf} 那五个值经注入的 {@link CreditScore} 加权的结果。 */
    public int scoreOf(TraderStats stats) {
        CreditBreakdown b = breakdownOf(stats);
        return score.compute(b.completed(), b.disputeRate(), b.positiveRate(),
                b.diversityShare(), b.activeDays());
    }

    /** 等级（笔数门槛优先于评分，见 {@link TraderTier}）。 */
    public TraderTier.Tier tierOf(TraderStats stats) {
        return TraderTier.tierOf(scoreOf(stats), stats.completed());
    }

    /** 面向用户的信用摘要——渲染方用它一次性拿到评分、等级与两个"必须一起报"的计数。 */
    public TraderCredit summarize(TraderStats stats) {
        requireStats(stats);
        return new TraderCredit(stats.userId(), scoreOf(stats), tierOf(stats),
                stats.completed(), stats.reviewCount(), diversityShareOf(stats));
    }

    /**
     * 面向用户的信用视图（互刷感知，④ 差距 3）：摘要 + 五维拆解 + <b>被剔除的笔数</b>。
     *
     * @param excludedTrades 剔除笔数——渲染层必须披露它，否则用户会发现自己"少了 8 笔"却查不到原因
     */
    public record CreditView(TraderCredit credit, CreditBreakdown breakdown, int excludedTrades) {
    }

    /**
     * 互刷感知的信用视图：先剔除可疑对手的交易，再算分与拆解。
     *
     * <p>摘要、评分、拆解三者都取自<b>同一份</b>剔除后统计——分别计算会出现"分数按 A 算、
     * 拆解按 B 算"的经典漂移（{@link #breakdownOf} 与 {@link #scoreOf} 同源的理由）。
     */
    public CreditView viewOf(TraderStats stats, java.util.function.LongFunction<TraderStats> lookup) {
        BrushAdjustment adjustment = adjustForBrush(stats, lookup);
        TraderStats effective = adjustment.effective();
        return new CreditView(summarize(effective), breakdownOf(effective), adjustment.excludedTrades());
    }

    /**
     * 榜单：把一组原始统计评分后<b>按评分降序</b>排列并截断到 {@code topN}。
     *
     * <p>排序刻意**不**留在渲染层（{@link Leaderboard#render} 自己也会按评分排一次）：
     * 截断必须发生在排序**之后**，否则"取前 10 名"会变成"取任意 10 人再排序"——
     * 那是榜单最经典的错法。这里先排后截，渲染层再排一次是幂等的。
     *
     * <p><b>同分定序</b>：评分相同时按用户 ID 升序，使同一批数据每次渲染结果一致
     * （榜单是公开可见的，顺序抖动会被读成"排名变了"）。
     *
     * @param all  参与者统计；{@code null} 或空 → 返回空列表（无成交史是正常状态，不是错误）
     * @param topN 取前几名（必须 ≥1；≤0 一律抛——静默返回全榜会让"榜单变短"看起来像"参与者少"）
     */
    public List<TraderCredit> rank(List<TraderStats> all, int topN) {
        if (topN < 1) {
            throw new com.tg.escrow.common.EscrowException("榜单：条数必须 ≥ 1，实为 " + topN);
        }
        if (all == null || all.isEmpty()) {
            return List.of();
        }
        // 互刷感知（④ 差距 3）：本次拿到的就是全部参与者，直接当对手查询用——榜单不必为
        // 每个对手再各查一次库（那是 O(N·M) 次查询）。查不到的对手按"数据不可得"不判可疑。
        java.util.Map<Long, TraderStats> universe = all.stream()
                .collect(java.util.stream.Collectors.toMap(TraderStats::userId, s -> s, (x, y) -> x));
        return all.stream()
                .map(stats -> summarize(adjustForBrush(stats, universe::get).effective()))
                .sorted(Comparator.comparingInt(TraderCredit::score).reversed()
                        .thenComparingLong(TraderCredit::userId))
                .limit(topN)
                .toList();
    }

    private static BigDecimal disputeRateOf(TraderStats stats) {
        if (stats.totalCount() == 0) {
            return BigDecimal.ZERO.setScale(SCALE);
        }
        return BigDecimal.valueOf(stats.disputeCount())
                .divide(BigDecimal.valueOf(stats.totalCount()), SCALE, RoundingMode.HALF_DOWN);
    }

    private static BigDecimal positiveRateOf(TraderStats stats) {
        if (stats.reviewCount() == 0) {
            return BigDecimal.ZERO.setScale(SCALE);
        }
        return BigDecimal.valueOf(stats.positiveReviews())
                .divide(BigDecimal.valueOf(stats.reviewCount()), SCALE, RoundingMode.HALF_DOWN);
    }

    private static void requireStats(TraderStats stats) {
        if (stats == null) {
            throw new com.tg.escrow.common.EscrowException("交易者信用：未提供原始统计");
        }
    }
}
