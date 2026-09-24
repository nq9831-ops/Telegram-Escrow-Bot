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
import java.util.Map;

/**
 * 交易对手多样性评分（ET-72）：同一对手交易占比过高 → 标可疑（互刷信号）。
 *
 * <h2>边界定死：严格超过阈值才算可疑</h2>
 * <p>核心规则「超过 60% 交易与同一人完成，标记可疑」——"超过"不含本数：
 * 恰 60% <b>不</b>可疑。该指标同时是排名评分（ET-75）的多样性维度，<b>一处定义、两处引用</b>。
 *
 * <p>空交易记录占比为 0、不可疑——无从判断时不臆断（与 {@link JoinRiskScorer} 的保守取向一致：
 * 数据缺失是采集问题，不是用户问题）。纯函数、无状态。
 */
public final class CounterpartyDiversityScorer {

    /** 占比计算的定点精度。 */
    private static final int SCALE = 4;

    private CounterpartyDiversityScorer() {
    }

    /**
     * 最大单一对手的交易占比。
     *
     * @param counterpartyCounts 对手 → 交易笔数（不得含负值）
     * @return 最大占比 [0,1]；空交易记录返回 0
     */
    public static BigDecimal maxShare(Map<Long, Integer> counterpartyCounts) {
        long total = totalOf(counterpartyCounts);
        if (total == 0) {
            return BigDecimal.ZERO.setScale(SCALE);
        }
        long max = counterpartyCounts.values().stream().mapToLong(Integer::longValue).max().orElse(0);
        return BigDecimal.valueOf(max).divide(BigDecimal.valueOf(total), SCALE, RoundingMode.HALF_DOWN);
    }

    /**
     * 是否标可疑：最大对手占比 <b>严格超过</b>阈值。
     *
     * @param counterpartyCounts 对手 → 交易笔数
     * @param threshold          可疑阈值（如 0.6；取值 (0,1]）
     */
    public static boolean isSuspicious(Map<Long, Integer> counterpartyCounts, BigDecimal threshold) {
        if (threshold == null || threshold.signum() <= 0 || threshold.compareTo(BigDecimal.ONE) > 0) {
            throw new EscrowException("对手多样性：阈值必须在 (0,1]，实为 " + threshold);
        }
        return maxShare(counterpartyCounts).compareTo(threshold) > 0;
    }

    private static long totalOf(Map<Long, Integer> counts) {
        if (counts == null) {
            throw new EscrowException("对手多样性：交易对手分布未提供");
        }
        long total = 0;
        for (Integer c : counts.values()) {
            if (c == null || c < 0) {
                throw new EscrowException("对手多样性：交易笔数不得为负，实为 " + c);
            }
            total += c;
        }
        return total;
    }
}
