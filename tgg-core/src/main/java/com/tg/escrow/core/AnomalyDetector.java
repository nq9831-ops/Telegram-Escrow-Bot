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
package com.tg.escrow.core;

import com.tg.escrow.common.TggException;

import java.math.BigDecimal;

/**
 * 账号行为突变检测（GM-27 / ET-73）：近期活跃相对基线突增 → 标可疑。
 *
 * <h2>只标记，不处罚</h2>
 * <p>行为突变可能是正常用户换设备（08 潜在冲突），所以输出是 {@link Verdict}——
 * <b>交人工复核</b>，本类不做任何自动惩罚决定。
 *
 * <h2>保守原则</h2>
 * <ul>
 *   <li><b>无基线（0）不判突变</b>：数据缺失是采集问题，不是用户问题（与
 *       {@link JoinRiskScorer} 同一取向）；</li>
 *   <li>判定为<b>严格超过</b>倍数因子——恰等于因子不算突变。</li>
 * </ul>
 *
 * <p>纯函数、无状态。
 */
public final class AnomalyDetector {

    /** 复核结论。 */
    public enum Verdict {
        /** 无突变。 */
        NORMAL,
        /** 标可疑——<b>仅标记</b>，需人工复核，不得自动处罚。 */
        SUSPICIOUS
    }

    private AnomalyDetector() {
    }

    /**
     * 判定行为是否突增。
     *
     * @param recentCount  近期活跃计数（不得为负）
     * @param baselineCount 基线活跃计数（不得为负；0 表示无基线 → 一概不判）
     * @param spikeFactor  突增倍数因子（≥ 1）
     * @return 严格超过 {@code baseline × factor} 时为 {@link Verdict#SUSPICIOUS}
     */
    public static Verdict assess(int recentCount, int baselineCount, BigDecimal spikeFactor) {
        if (recentCount < 0 || baselineCount < 0) {
            throw new TggException("异常检测：计数不得为负（近期 " + recentCount
                    + "，基线 " + baselineCount + "）");
        }
        if (spikeFactor == null || spikeFactor.compareTo(BigDecimal.ONE) < 0) {
            throw new TggException("异常检测：突增因子必须 ≥ 1，实为 " + spikeFactor);
        }
        if (baselineCount == 0) {
            return Verdict.NORMAL;
        }
        BigDecimal threshold = BigDecimal.valueOf(baselineCount).multiply(spikeFactor);
        return BigDecimal.valueOf(recentCount).compareTo(threshold) > 0
                ? Verdict.SUSPICIOUS
                : Verdict.NORMAL;
    }
}
