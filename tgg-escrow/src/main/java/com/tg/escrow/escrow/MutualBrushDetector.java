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
import java.util.Map;

/**
 * 互刷模式检测（ET-65）：A 和 B <b>反复互相交易</b> → 标可疑。
 *
 * <h2>双向互指才算互刷</h2>
 * <p>与 {@link CounterpartyDiversityScorer}（单向：一方过度依赖单一对手）互补——
 * 大客户关系天然单向集中，不该误伤。互刷形态是<b>双方都指向彼此</b>：
 * A 的交易集中于 B、且 B 的交易集中于 A。两侧占比都<b>严格超过</b>阈值才判可疑。
 *
 * <p>复用 {@code CounterpartyDiversityScorer.isSuspicious} 的占比口径（一处定义）。
 * 只标记不处罚（交人工复核）。返回 {@code boolean}——本模块不依赖 tgg-core
 * （依赖方向约束），判定枚举语义由调用方映射。纯函数、无状态。
 */
public final class MutualBrushDetector {

    private MutualBrushDetector() {
    }

    /**
     * 判定 A/B 是否构成互刷形态。
     *
     * @param userA        用户 A ID
     * @param aCounts      A 的对手分布（对手 → 笔数）
     * @param userB        用户 B ID
     * @param bCounts      B 的对手分布
     * @param concentrationThreshold 集中度阈值（(0,1]，如 0.6）
     * @return {@code true} = 双向互指均超阈值，标可疑
     */
    public static boolean isSuspicious(long userA, Map<Long, Integer> aCounts,
                                       long userB, Map<Long, Integer> bCounts,
                                       BigDecimal concentrationThreshold) {
        if (aCounts == null || bCounts == null) {
            throw new EscrowException("互刷检测：对手分布未提供");
        }
        if (concentrationThreshold == null
                || concentrationThreshold.signum() <= 0
                || concentrationThreshold.compareTo(BigDecimal.ONE) > 0) {
            throw new EscrowException("互刷检测：阈值必须在 (0,1]，实为 " + concentrationThreshold);
        }
        // 「集中于对方」= 对方占本人总交易的比例严格超过阈值（与 DiversityScorer 的占比口径同族）
        BigDecimal aShare = shareOf(aCounts, userB);
        BigDecimal bShare = shareOf(bCounts, userA);
        return aShare.compareTo(concentrationThreshold) > 0
                && bShare.compareTo(concentrationThreshold) > 0;
    }

    private static BigDecimal shareOf(Map<Long, Integer> counts, long counterpart) {
        long total = 0;
        for (Integer c : counts.values()) {
            if (c == null || c < 0) {
                throw new EscrowException("互刷检测：交易笔数不得为负，实为 " + c);
            }
            total += c;
        }
        if (total == 0) {
            return BigDecimal.ZERO.setScale(4);
        }
        return BigDecimal.valueOf(counts.getOrDefault(counterpart, 0))
                .divide(BigDecimal.valueOf(total), 4, java.math.RoundingMode.HALF_DOWN);
    }
}
