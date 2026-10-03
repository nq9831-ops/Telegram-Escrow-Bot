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

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * {@code /escrow credit} 的文案渲染——把 {@link TraderCredit}（这人怎么样）与
 * {@link CreditBreakdown}（分数由哪几项构成）写成用户能读懂、能据此改进的一段话。
 *
 * <h2>三条写作纪律（每条都对应一个真实误读风险）</h2>
 * <ol>
 *   <li><b>不只报分数</b>：{@link CreditScore} 的两个反向维度（争议率、对手集中度）在
 *       "数据为零"时给<b>满分</b>，于是零历史用户也有 40 分。只报"40 分"会让新人
 *       以为白得了信用。故笔数与五个维度必须同时出现。</li>
 *   <li><b>无评价要标注</b>：{@code reviewCount == 0} 时明说"暂无评价"——否则 "好评率 0%"
 *       读起来像"被人打了差评"，而事实是"还没人评价过"。</li>
 *   <li><b>零历史要解释来源</b>：直接点明那 40 分来自哪两个维度，避免被当作实际信用。</li>
 * </ol>
 *
 * <p>本类只做文案与格式化；比率怎么算是 {@link TraderCreditService} 的事，
 * 分数量表（满分线）取自 {@link CreditScore} 的公开常量，不在此另写一份。
 *
 * <p>纯函数、无状态。{@code null} 入参 fail-closed——宁可失败，也不渲染半截文案。
 */
public final class CreditSummaryView {

    private CreditSummaryView() {
    }

    /**
     * 渲染信用摘要。
     *
     * @param credit    面向用户的信用摘要（评分/等级/笔数/评价数）
     * @param breakdown 五维拆解（与 {@code credit} 由同一次统计派生）
     * @return 可直接回给用户的文案
     */
    public static String render(TraderCredit credit, CreditBreakdown breakdown) {
        return render(credit, breakdown, 0);
    }

    /**
     * 渲染信用摘要（含互刷剔除披露，④ 差距 3）。
     *
     * @param excludedTrades 因互刷嫌疑被剔除的笔数；{@code > 0} 时文案必须说出来——
     *                       用户会拿它和记忆里的笔数对账，静默改数只会让人以为数据错了
     */
    public static String render(TraderCredit credit, CreditBreakdown breakdown, int excludedTrades) {
        if (credit == null || breakdown == null) {
            throw new EscrowException("信用摘要：信用与维度拆解均不可为空");
        }
        if (excludedTrades < 0) {
            throw new EscrowException("信用摘要：剔除笔数不得为负");
        }
        StringBuilder sb = new StringBuilder();
        sb.append("📊 你的信用摘要\n")
                .append(credit.tier().badge()).append(' ').append(tierName(credit.tier()))
                .append(" · 评分 ").append(credit.score()).append("/100\n")
                .append("完成交易 ").append(credit.completed()).append(" 笔 · 收到评价 ")
                .append(credit.reviewCount()).append(" 条")
                .append(credit.reviewCount() == 0 ? "（暂无评价）" : "").append('\n')
                .append("维度拆解：\n")
                .append("· 完成笔数：").append(breakdown.completed()).append('/')
                .append(CreditScore.COMPLETED_FULL).append('\n')
                .append("· 争议率：").append(percent(breakdown.disputeRate()))
                .append("（反向：越低越好）\n")
                .append("· 好评率：").append(percent(breakdown.positiveRate())).append('\n')
                .append("· 对手集中度：").append(percent(breakdown.diversityShare()))
                .append("（反向：越低越好）\n")
                .append("· 活跃时长：").append(breakdown.activeDays()).append('/')
                .append(CreditScore.ACTIVE_DAYS_FULL).append(" 天")
                .append(decayNote(breakdown.decayFactor()));
        if (excludedTrades > 0) {
            sb.append("\n\nℹ️ 其中 ").append(excludedTrades)
                    .append(" 笔与同一对手的双向集中度超阈值（互刷嫌疑），按「互刷不累加」未计入上列统计。\n")
                    .append("判定与风控评估同源，仅影响计分、不代表违规认定。");
        }
        // 只有**真的没有成交史**才说「尚无成交记录」。被互刷剔除成 0 笔是另一回事：
        // 用户明明有交易，说"尚无成交记录"是假话，且与上面的剔除披露自相矛盾（真机核销逮到）。
        if (excludedTrades == 0 && credit.completed() == 0 && credit.reviewCount() == 0) {
            // 措辞只陈述**可验证的事实**（两项为 0）与**一般机制**（反向维度在数据为零时按满分计），
            // 刻意不声称分数"由哪几个维度构成"：该分支下活跃时长是**正向**维度，可贡献至多约 10 分，
            // 而这句子对「有订单未完成（对手集中度 100%、该项 0 分）」与「活跃期很长」两种形态都成立。
            // 历史教训：曾写「全部来自两个反向维度的满分」——线上实测撞到假话（见 ONLINE-VERIFICATION）。
            sb.append("\n\n⚠️ 尚无成交记录：完成笔数与好评率均为 0；评分里「反向维度」在数据为零时")
                    .append("按满分计，故当前的 ").append(credit.score())
                    .append(" 分不代表实际信用。");
        }
        return sb.toString();
    }

    /**
     * 时间衰减披露（④ 差距 2）：因子 &lt; 1 时必须说出来——用户会拿活跃天数和记忆对账，
     * 不解释就只像"数据算错了"。因子为 1（未启用衰减或本来就没衰减）时**一个字都不加**。
     */
    private static String decayNote(BigDecimal decayFactor) {
        if (decayFactor == null || decayFactor.compareTo(BigDecimal.ONE) >= 0) {
            return "";
        }
        return "（最近活跃距今较久，按时间衰减 ×" + decayFactor.stripTrailingZeros().toPlainString() + "）";
    }

    /** 等级徽标只有图形，文案里补上中文名——🌱 对多数用户并不自明。 */
    private static String tierName(TraderTier.Tier tier) {
        return switch (tier) {
            case NEWBIE -> "新手";
            case BRONZE -> "铜牌";
            case SILVER -> "银牌";
            case GOLD -> "金牌";
            case DIAMOND -> "钻石";
        };
    }

    /** 比率 → 整数百分比。用户读"20%"就够，小数点后四位只是口径的精度，不是展示的精度。 */
    private static String percent(BigDecimal rate) {
        return rate.multiply(BigDecimal.valueOf(100))
                .setScale(0, RoundingMode.HALF_UP).toPlainString() + "%";
    }
}
