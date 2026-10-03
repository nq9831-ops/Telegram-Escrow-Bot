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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link Leaderboard}（ET-77/78/79）分支级行为固定测试。
 *
 * <p>现有 {@link RankingTest} 只覆盖"截断 + 匿名不泄名 + 空榜非空白"三条松路径；
 * 本类补齐 fail-closed 抛错、乱序输入排序（渲染契约「无需预评分」）、
 * {@code anonymize=false} 分支与同分稳定序——后者是服务层「同分按 ID 定序」
 * 传递到输出的依赖（渲染层稳定排序保持输入顺序）。
 */
class LeaderboardTest {

    private static final String EMPTY_BOARD = "📊 本期榜单：暂无数据";

    @Test
    @DisplayName("fail-closed：条目列表未提供 / topN < 1 → EscrowException")
    void invalidArgumentsRejected() {
        assertThatThrownBy(() -> Leaderboard.render(null, 10, true))
                .isInstanceOf(EscrowException.class);
        assertThatThrownBy(() -> Leaderboard.render(List.of(), 0, true))
                .isInstanceOf(EscrowException.class);
        assertThatThrownBy(() -> Leaderboard.render(List.of(), -1, true))
                .isInstanceOf(EscrowException.class);
    }

    @Test
    @DisplayName("空榜文案逐字固定：📊 本期榜单：暂无数据")
    void emptyBoardWordingIsPinned() {
        assertThat(Leaderboard.render(List.of(), 10, true)).isEqualTo(EMPTY_BOARD);
    }

    @Test
    @DisplayName("无需预排序：乱序输入按评分降序渲染")
    void unsortedInputIsRenderedInScoreOrder() {
        List<Leaderboard.Entry> entries = List.of(
                new Leaderboard.Entry("carol", 75, Tier.SILVER, false),
                new Leaderboard.Entry("alice", 95, Tier.DIAMOND, false),
                new Leaderboard.Entry("bob", 85, Tier.GOLD, false));
        String board = Leaderboard.render(entries, 3, true);

        int first = board.indexOf(RankPrivacy.display("alice", false));
        int second = board.indexOf(RankPrivacy.display("bob", false));
        int third = board.indexOf(RankPrivacy.display("carol", false));
        assertThat(first).isNotNegative();
        assertThat(second).isGreaterThan(first);
        assertThat(third).isGreaterThan(second);
    }

    @Test
    @DisplayName("同分条目保持输入顺序（稳定排序）——服务层同分定序向输出传递")
    void equalScoresPreserveInputOrder() {
        List<Leaderboard.Entry> entries = List.of(
                new Leaderboard.Entry("userZ", 80, Tier.GOLD, true),
                new Leaderboard.Entry("userA", 80, Tier.GOLD, true),
                new Leaderboard.Entry("userM", 80, Tier.GOLD, true));
        String board = Leaderboard.render(entries, 3, false);

        int z = board.indexOf("userZ");
        int a = board.indexOf("userA");
        int m = board.indexOf("userM");
        assertThat(z).isNotNegative();
        assertThat(a).isGreaterThan(z);
        assertThat(m).isGreaterThan(a);
    }

    @Test
    @DisplayName("anonymize=true 是强制匿名：即使用户已公开也脱敏")
    void anonymizeOverridesPublicProfile() {
        List<Leaderboard.Entry> entries = List.of(
                new Leaderboard.Entry("alice", 95, Tier.DIAMOND, true));
        String board = Leaderboard.render(entries, 1, true);

        assertThat(board).doesNotContain("alice")
                .contains(RankPrivacy.display("alice", false));
    }

    @Test
    @DisplayName("anonymize=false 尊重公开设置：公开显示全名、非公开脱敏")
    void nonAnonymizedBoardFollowsPublicFlag() {
        List<Leaderboard.Entry> entries = List.of(
                new Leaderboard.Entry("alice", 95, Tier.DIAMOND, true),
                new Leaderboard.Entry("bob", 85, Tier.GOLD, false));
        String board = Leaderboard.render(entries, 2, false);

        assertThat(board).contains("alice").doesNotContain("bob")
                .contains(RankPrivacy.display("bob", false));
    }

    @Test
    @DisplayName("截断到 topN 条且奖牌只发前三：第 4 条起无奖牌前缀")
    void truncationAndMedalPrefix() {
        List<Leaderboard.Entry> entries = List.of(
                new Leaderboard.Entry("userA", 95, Tier.DIAMOND, true),
                new Leaderboard.Entry("userB", 85, Tier.GOLD, true),
                new Leaderboard.Entry("userC", 75, Tier.SILVER, true),
                new Leaderboard.Entry("userD", 65, Tier.BRONZE, true));
        String board = Leaderboard.render(entries, 4, false);

        assertThat(board).contains("🥇").contains("🥈").contains("🥉");
        assertThat(board).containsPattern("(?m)^ {2} userD");
    }
}
