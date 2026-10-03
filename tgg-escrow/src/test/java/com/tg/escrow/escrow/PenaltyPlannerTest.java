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
import com.tg.escrow.escrow.PenaltyPlanner.Penalty;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 多维度惩罚（ET-61）的行为固定测试。
 *
 * <p>核心规则「单笔限制+冷却+投票暂停+公示+链上存证」的<b>触发侧</b>：按违规史组合惩罚，
 * 各维度独立可叠加。触发阈值定死：警告 ≥3 → 交易限制+冷却加严；争议败诉 ≥2 → 投票暂停；
 * 可疑标记 → 公示。零违规 → 空计划（不预罚）。
 */
class PenaltyPlannerTest {

    @Test
    @DisplayName("警告 ≥3 → 交易限制 + 冷却加严")
    void warnThresholdTradesLimit() {
        EnumSet<Penalty> plan = PenaltyPlanner.plan(3, 0, false);

        assertThat(plan).containsExactlyInAnyOrder(Penalty.TRADE_LIMIT, Penalty.COOLDOWN_EXTEND);
    }

    @Test
    @DisplayName("争议败诉 ≥2 → 投票暂停")
    void disputeLossesSuspendVote() {
        EnumSet<Penalty> plan = PenaltyPlanner.plan(0, 2, false);

        assertThat(plan).containsExactly(Penalty.VOTE_SUSPEND);
    }

    @Test
    @DisplayName("可疑标记 → 公示")
    void suspiciousFlagPublicNotice() {
        EnumSet<Penalty> plan = PenaltyPlanner.plan(0, 0, true);

        assertThat(plan).containsExactly(Penalty.PUBLIC_NOTICE);
    }

    @Test
    @DisplayName("多维叠加：全触发 → 全维度")
    void stacked() {
        EnumSet<Penalty> plan = PenaltyPlanner.plan(5, 3, true);

        assertThat(plan).containsExactlyInAnyOrder(Penalty.TRADE_LIMIT, Penalty.COOLDOWN_EXTEND,
                Penalty.VOTE_SUSPEND, Penalty.PUBLIC_NOTICE);
    }

    @Test
    @DisplayName("零违规 → 空计划（不预罚）")
    void cleanRecordNoPenalty() {
        assertThat(PenaltyPlanner.plan(0, 0, false)).isEmpty();
    }

    @Test
    @DisplayName("负计数 → fail-closed")
    void negativeCountsRejected() {
        assertThatThrownBy(() -> PenaltyPlanner.plan(-1, 0, false))
                .isInstanceOf(EscrowException.class);
    }
}
