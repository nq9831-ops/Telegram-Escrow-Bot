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

/** 交易信用综合评分（ET-75）：完成数 30% + 争议率（反向）25% + 好评率 20% + 对手多样性 15% + 活跃时长 10%。 */
public final class CreditScore {

    private static final BigDecimal[] WEIGHTS = {
            new BigDecimal("0.30"), new BigDecimal("0.25"), new BigDecimal("0.20"),
            new BigDecimal("0.15"), new BigDecimal("0.10")};

    private CreditScore() {
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
    public static int compute(int completed, BigDecimal disputeRate, BigDecimal positiveRate,
                              BigDecimal diversityShare, int activeDays) {
        if (completed < 0 || activeDays < 0) {
            throw new EscrowException("信用评分：计数不得为负");
        }
        BigDecimal[] scores = {
                ratio(completed, 50),
                BigDecimal.ONE.subtract(rate(disputeRate)),
                rate(positiveRate),
                BigDecimal.ONE.subtract(rate(diversityShare)),
                ratio(activeDays, 365)};
        BigDecimal total = BigDecimal.ZERO;
        for (int i = 0; i < WEIGHTS.length; i++) {
            total = total.add(scores[i].multiply(WEIGHTS[i]));
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
