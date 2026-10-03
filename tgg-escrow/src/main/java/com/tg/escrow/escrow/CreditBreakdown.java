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

/**
 * 信用评分的**五维拆解**——{@code /escrow credit} 解释"这个分数从哪来"的输入。
 *
 * <h2>为什么单独存在（而不是让渲染层自己算）</h2>
 * <p>这五个值正是 {@link CreditScore#compute} 的入参，但它要的是"算好的比率"，
 * 而比率怎么算（争议率的分母是谁、无评价取 0 还是满分、活跃天数含不含两端）属于
 * <b>口径</b>，只该在 {@link TraderCreditService} 里出现一次。渲染层若自行换算，
 * 就会出现"文案解释的是 A 套口径、分数来自 B 套口径"的分叉——用户看到的拆解加不回总分。
 * 故此处只承载<b>算好的值</b>，且由 {@link TraderCreditService#breakdownOf} 产出。
 *
 * <p>它是 {@link TraderCredit} 的补充而非替代：{@code TraderCredit} 面向"这人怎么样"
 * （评分、等级、笔数），本记录面向"分数由哪几项构成"（五个维度）。两者由同一次统计派生。
 *
 * <p>构造期拦的是**永远不该成立的值**：负计数、越界的比率。这些一旦放行，渲染出的就是
 * 一个用户无法理解、也无法据此改进的拆解。
 *
 * @param completed      完成笔数（口径见 {@link TraderStats}）
 * @param disputeRate    争议率 [0,1]（反向维度：越低越好）
 * @param positiveRate   好评率 [0,1]；无评价时为 0
 * @param diversityShare 最大单一对手占比 [0,1]（反向维度：越低越好）
 * @param activeDays     活跃天数（首末自然日跨度，含两端）
 * @param decayFactor    时间衰减因子 (0,1]（④ 差距 2）：{@code activeDays} 已按它折过；
 *                       因子 &lt; 1 时渲染层须披露，否则用户会发现天数比记忆里短却查不到原因
 */
public record CreditBreakdown(int completed, BigDecimal disputeRate, BigDecimal positiveRate,
                              BigDecimal diversityShare, int activeDays, BigDecimal decayFactor) {

    /** 不衰减的拆解（因子 1）——多数调用方的口径，故保留五参形态。 */
    public CreditBreakdown(int completed, BigDecimal disputeRate, BigDecimal positiveRate,
                           BigDecimal diversityShare, int activeDays) {
        this(completed, disputeRate, positiveRate, diversityShare, activeDays, BigDecimal.ONE);
    }

    public CreditBreakdown {
        if (completed < 0 || activeDays < 0) {
            throw new EscrowException("信用拆解：计数不得为负");
        }
        requireRate(disputeRate, "争议率");
        requireRate(positiveRate, "好评率");
        requireRate(diversityShare, "对手集中度");
        if (decayFactor == null || decayFactor.signum() <= 0
                || decayFactor.compareTo(BigDecimal.ONE) > 0) {
            throw new EscrowException("信用拆解：衰减因子必须在 (0,1]，实为 " + decayFactor);
        }
    }

    private static void requireRate(BigDecimal rate, String what) {
        if (rate == null || rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) > 0) {
            throw new EscrowException("信用拆解：" + what + " 必须在 [0,1]，实为 " + rate);
        }
    }
}
