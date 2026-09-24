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

import java.util.EnumSet;

/**
 * 多维度惩罚（ET-61）的<b>触发侧</b>：按违规史组合惩罚维度。
 *
 * <h2>触发规则（阈值定死）</h2>
 * <ul>
 *   <li>警告 ≥ 3 → {@link Penalty#TRADE_LIMIT}（单笔限制加严）+ {@link Penalty#COOLDOWN_EXTEND}（冷却加严）；</li>
 *   <li>争议败诉 ≥ 2 → {@link Penalty#VOTE_SUSPEND}（投票暂停）；</li>
 *   <li>可疑标记 → {@link Penalty#PUBLIC_NOTICE}（公示；链上存证随公示由调用方上链）。</li>
 * </ul>
 *
 * <p>各维度独立可叠加；<b>零违规 → 空计划</b>（不预罚）。纯函数、无状态。
 */
public final class PenaltyPlanner {

    /** 惩罚维度。 */
    public enum Penalty {
        /** 单笔限制加严。 */
        TRADE_LIMIT,
        /** 冷却期加严。 */
        COOLDOWN_EXTEND,
        /** 投票暂停。 */
        VOTE_SUSPEND,
        /** 公示（含链上存证，由调用方执行）。 */
        PUBLIC_NOTICE
    }

    /** 触发 TRADE_LIMIT/COOLDOWN_EXTEND 的警告阈值。 */
    public static final int WARN_THRESHOLD = 3;
    /** 触发 VOTE_SUSPEND 的争议败诉阈值。 */
    public static final int DISPUTE_LOSS_THRESHOLD = 2;

    private PenaltyPlanner() {
    }

    /**
     * 生成惩罚计划。
     *
     * @param warnCount       累计警告数（不得为负）
     * @param disputeLosses   争议败诉次数（不得为负）
     * @param suspiciousFlag  是否被标记可疑（{@code DeviceLinkAnalyzer}/{@code MutualBrushDetector} 等）
     */
    public static EnumSet<Penalty> plan(int warnCount, int disputeLosses, boolean suspiciousFlag) {
        if (warnCount < 0 || disputeLosses < 0) {
            throw new EscrowException("惩罚计划：计数不得为负（警告 " + warnCount
                    + "，败诉 " + disputeLosses + "）");
        }
        EnumSet<Penalty> plan = EnumSet.noneOf(Penalty.class);
        if (warnCount >= WARN_THRESHOLD) {
            plan.add(Penalty.TRADE_LIMIT);
            plan.add(Penalty.COOLDOWN_EXTEND);
        }
        if (disputeLosses >= DISPUTE_LOSS_THRESHOLD) {
            plan.add(Penalty.VOTE_SUSPEND);
        }
        if (suspiciousFlag) {
            plan.add(Penalty.PUBLIC_NOTICE);
        }
        return plan;
    }
}
