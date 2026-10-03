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
 * 交易信用综合评分（ET-75）：完成数 30% + 争议率（反向）25% + 好评率 20% + 对手多样性 15% + 活跃时长 10%。
 *
 * <h2>为什么从纯静态类改成实例（④ 差距 1）</h2>
 * <p>权重原先是硬编码常量，部署者无法按自己的运营口径调整；配置化后权重来自
 * {@code tgg.credit.weights}，而「5 项之和 ≠ 1」「项越界」这类配置事故必须<b>启动即炸</b>
 * ——静态工具类没有构造时机，实例类的构造器才是 fail-fast 的落点。
 *
 * <p>{@link #defaults()} 保持既有口径（现网行为零漂移）：不配置就用它。
 */
public final class CreditScore {

    private static final BigDecimal[] DEFAULT_WEIGHTS = {
            new BigDecimal("0.30"), new BigDecimal("0.25"), new BigDecimal("0.20"),
            new BigDecimal("0.15"), new BigDecimal("0.10")};

    /** 五维模型的维度数——多一项少一项都是口径错误，不是「多算一个维度」。 */
    private static final int DIMENSIONS = 5;

    /** 完成数维度的量表上限：≥ 该值即该维度满分（渲染层引同一常量，量表不两处各写一份）。 */
    public static final int COMPLETED_FULL = 50;

    /** 活跃时长维度的量表上限（天）：≥ 该值即该维度满分。 */
    public static final int ACTIVE_DAYS_FULL = 365;

    private final BigDecimal[] weights;

    /** 既有口径（权重 0.30/0.25/0.20/0.15/0.10）——不配置时用它，行为零漂移。 */
    public static CreditScore defaults() {
        return new CreditScore(DEFAULT_WEIGHTS);
    }

    /**
     * @param weights 五维权重（每项 ∈ [0,1]，<b>总和必须为 1</b>——不等于 1 时分数系统性偏移，
     *                是权重配置最常见的事故，构造期拒绝）
     * @throws EscrowException 项数不是 5、有项越界、或总和 ≠ 1
     */
    public CreditScore(BigDecimal[] weights) {
        if (weights == null || weights.length != DIMENSIONS) {
            throw new EscrowException("信用评分：权重必须是 " + DIMENSIONS + " 项，实为 "
                    + (weights == null ? "null" : weights.length));
        }
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal w : weights) {
            if (w == null || w.signum() < 0 || w.compareTo(BigDecimal.ONE) > 0) {
                throw new EscrowException("信用评分：每项权重必须在 [0,1]，实含非法项 " + w);
            }
            total = total.add(w);
        }
        if (total.compareTo(BigDecimal.ONE) != 0) {
            throw new EscrowException("信用评分：权重总和必须为 1，实为 " + total.stripTrailingZeros().toPlainString());
        }
        this.weights = weights.clone();
    }

    /**
     * 计算 0–100 综合评分。
     *
     * @param completed      完成交易数（≥0；≥50 笔即该维度满分——量表饱和）
     * @param disputeRate    争议率 [0,1]（<b>反向</b>：越低分越高）
     * @param positiveRate   好评率 [0,1]
     * @param diversityShare 最大对手占比 [0,1]（多样性反向：占比越低分越高，来自 ET-72）
     * @param activeDays     账号活跃天数（≥0；≥365 天该维度满分）
     */
    public int compute(int completed, BigDecimal disputeRate, BigDecimal positiveRate,
                       BigDecimal diversityShare, int activeDays) {
        if (completed < 0 || activeDays < 0) {
            throw new EscrowException("信用评分：计数不得为负");
        }
        BigDecimal[] scores = {
                ratio(completed, COMPLETED_FULL),
                BigDecimal.ONE.subtract(rate(disputeRate)),
                rate(positiveRate),
                BigDecimal.ONE.subtract(rate(diversityShare)),
                ratio(activeDays, ACTIVE_DAYS_FULL)};
        BigDecimal total = BigDecimal.ZERO;
        for (int i = 0; i < weights.length; i++) {
            total = total.add(scores[i].multiply(weights[i]));
        }
        return total.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP).intValue();
    }

    private static BigDecimal rate(BigDecimal r) {
        if (r == null || r.signum() < 0 || r.compareTo(BigDecimal.ONE) > 0) {
            throw new EscrowException("信用评分：比率必须在 [0,1]，实为 " + r);
        }
        return r;
    }

    private static BigDecimal ratio(int value, int full) {
        return BigDecimal.valueOf(Math.min(value, full))
                .divide(BigDecimal.valueOf(full), 4, RoundingMode.HALF_DOWN);
    }
}
