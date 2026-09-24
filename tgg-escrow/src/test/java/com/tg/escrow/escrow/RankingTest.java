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
import com.tg.escrow.escrow.TraderTier.Tier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 排名机制（ET-75~80）的行为固定测试。
 *
 * <p>覆盖：综合评分加权、等级评定（<b>笔数门槛优先</b>——13 号材料 2.1 冲突定案）、
 * 隐私脱敏（默认不公开用户名）、榜单渲染（条数上限 + 脱敏）。
 */
class RankingTest {

    @Test
    @DisplayName("综合评分：五个维度按 30/25/20/15/10 加权（满分=100）")
    void creditScoreWeighted() {
        // 满分：完成数饱和、零争议、好评 100%、对手完全分散（share=0 反向最优）、活跃 365 天
        int perfect = CreditScore.compute(50, BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ZERO, 365);
        assertThat(perfect).isEqualTo(100);

        // 只有完成数维度得分：争议 100%、零好评、单一对手（share=1 反向 0 分）、零活跃 → 30
        int onlyTrades = CreditScore.compute(50, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, 0);
        assertThat(onlyTrades).isEqualTo(30);
    }

    @Test
    @DisplayName("争议率反向计分：争议率 100% 该维度为 0")
    void disputeRateReverse() {
        int clean = CreditScore.compute(10, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0);
        int dirty = CreditScore.compute(10, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, 0);

        assertThat(clean).isGreaterThan(dirty);
    }

    @Test
    @DisplayName("维度值越界 / 负笔数 → fail-closed")
    void creditScoreInvalid() {
        assertThatThrownBy(() -> CreditScore.compute(-1, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, 0)).isInstanceOf(EscrowException.class);
        assertThatThrownBy(() -> CreditScore.compute(1, new BigDecimal("1.5"), BigDecimal.ZERO,
                BigDecimal.ZERO, 0)).isInstanceOf(EscrowException.class);
    }

    @Test
    @DisplayName("等级冲突定案：笔数门槛优先——高分但 <5 笔仍是新手")
    void tierCountThresholdWins() {
        assertThat(TraderTier.tierOf(95, 4)).isEqualTo(Tier.NEWBIE);   // 评分 95 但只有 4 笔
        assertThat(TraderTier.tierOf(95, 50)).isEqualTo(Tier.DIAMOND);
        assertThat(TraderTier.tierOf(85, 20)).isEqualTo(Tier.GOLD);
        assertThat(TraderTier.tierOf(75, 10)).isEqualTo(Tier.SILVER);
        assertThat(TraderTier.tierOf(65, 5)).isEqualTo(Tier.BRONZE);
        assertThat(TraderTier.tierOf(59, 5)).isEqualTo(Tier.NEWBIE);   // 满 5 笔但信用不足归新手
    }

    @Test
    @DisplayName("隐私默认脱敏；用户选择公开才显示全名")
    void privacyMasksByDefault() {
        assertThat(RankPrivacy.display("userA", false)).doesNotContain("userA").contains("u");
        assertThat(RankPrivacy.display("userA", true)).isEqualTo("userA");
    }

    @Test
    @DisplayName("榜单渲染：截断到 N 条、奖牌前缀、匿名榜不泄完整用户名")
    void leaderboardRender() {
        List<Leaderboard.Entry> entries = List.of(
                new Leaderboard.Entry("alice", 95, Tier.DIAMOND, false),
                new Leaderboard.Entry("bob", 85, Tier.GOLD, true),
                new Leaderboard.Entry("carol", 75, Tier.SILVER, false));
        String board = Leaderboard.render(entries, 2, true);

        assertThat(board).contains("🥇").contains("🥈").doesNotContain("🥉"); // 只渲染 2 条
        assertThat(board).doesNotContain("alice").doesNotContain("bob");      // 匿名榜不泄完整名
    }

    @Test
    @DisplayName("空榜 → 明示暂无数据（不渲染空白）")
    void emptyBoard() {
        assertThat(Leaderboard.render(List.of(), 10, true)).isNotBlank();
    }
}
