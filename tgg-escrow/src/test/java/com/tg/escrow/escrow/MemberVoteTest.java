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
import com.tg.escrow.escrow.MemberVote.Choice;
import com.tg.escrow.escrow.MemberVote.Outcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 群成员投票（ET-41）的行为固定测试。
 *
 * <p>三条硬规则：①<b>加权计票</b>（权重来自 {@link VoterRegistry} 分层）；
 * ②<b>观察员与重复投票 fail-closed</b>；③<b>截止后不可投票</b>、结果含"未达门槛"
 * （为 {@link FallbackSettle} 兜底留出判定点）。
 */
class MemberVoteTest {

    private static final Instant T0 = Instant.parse("2026-09-23T00:00:00Z");
    private static final long BUYER = 1001L;
    private static final long SELLER = 2002L;

    /** 无人回避、买卖双方不在票池（VoterRecusal 由调用方先过滤）。 */
    private static Set<Long> noRecent(long voterId) {
        return Set.of();
    }

    private static MemberVote vote(int quorum) {
        return new MemberVote(BUYER, SELLER, quorum, T0.plusSeconds(72 * 3600));
    }

    @Test
    @DisplayName("加权计票：核心(3) vs 普通(1)——RELEASE 多出即胜")
    void weightedTally() {
        MemberVote v = vote(2);

        v.cast(1L, VoterRegistry.Tier.CORE, Choice.REFUND, T0);      // REFUND 3
        v.cast(2L, VoterRegistry.Tier.REGULAR, Choice.RELEASE, T0);  // RELEASE 1
        v.cast(3L, VoterRegistry.Tier.REGULAR, Choice.RELEASE, T0);  // RELEASE 2
        v.cast(4L, VoterRegistry.Tier.REGULAR, Choice.RELEASE, T0);  // RELEASE 3 —— 3 vs 3 平
        v.cast(5L, VoterRegistry.Tier.REGULAR, Choice.RELEASE, T0);  // RELEASE 4 > 3

        assertThat(v.result(T0)).isEqualTo(Outcome.RELEASE);
    }

    @Test
    @DisplayName("REFUND 胜出时结论为 REFUND")
    void refundWins() {
        MemberVote v = vote(2);

        v.cast(1L, VoterRegistry.Tier.CORE, Choice.REFUND, T0);
        v.cast(2L, VoterRegistry.Tier.SENIOR, Choice.REFUND, T0);

        assertThat(v.result(T0)).isEqualTo(Outcome.REFUND);
    }

    @Test
    @DisplayName("参与人数不足门槛 → INSUFFICIENT（交给 FallbackSettle 兜底）")
    void belowQuorumIsInsufficient() {
        MemberVote v = vote(3);

        v.cast(1L, VoterRegistry.Tier.CORE, Choice.RELEASE, T0);

        assertThat(v.result(T0)).isEqualTo(Outcome.INSUFFICIENT);
    }

    @Test
    @DisplayName("观察员投票 → 拒绝（无投票权）")
    void observerCannotVote() {
        MemberVote v = vote(1);

        assertThatThrownBy(() -> v.cast(1L, VoterRegistry.Tier.OBSERVER, Choice.RELEASE, T0))
                .isInstanceOf(EscrowException.class);
    }

    @Test
    @DisplayName("重复投票 → 拒绝（防刷票）")
    void doubleVoteRejected() {
        MemberVote v = vote(1);

        v.cast(1L, VoterRegistry.Tier.CORE, Choice.RELEASE, T0);
        assertThatThrownBy(() -> v.cast(1L, VoterRegistry.Tier.CORE, Choice.REFUND, T0))
                .isInstanceOf(EscrowException.class);
    }

    @Test
    @DisplayName("截止后投票 → 拒绝（投票时间线到点即止）")
    void votingAfterDeadlineRejected() {
        MemberVote v = vote(1);

        assertThatThrownBy(() -> v.cast(1L, VoterRegistry.Tier.CORE, Choice.RELEASE,
                T0.plusSeconds(73 * 3600)))
                .isInstanceOf(EscrowException.class);
    }

    @Test
    @DisplayName("回避校验：与买卖双方有近期交易者、买卖双方本人不得投票")
    void recusedVoterRejected() {
        MemberVote v = vote(1);

        assertThatThrownBy(() -> v.cast(BUYER, VoterRegistry.Tier.CORE, Choice.RELEASE, T0))
                .isInstanceOf(EscrowException.class);
        assertThatThrownBy(() -> v.castWithRecusal(9L, VoterRegistry.Tier.CORE, Choice.RELEASE,
                Set.of(BUYER), T0))
                .isInstanceOf(EscrowException.class);
    }

    @Test
    @DisplayName("构造期校验：门槛 < 1 / 截止时刻为空 → 拒绝")
    void invalidConfigRejected() {
        assertThatThrownBy(() -> new MemberVote(BUYER, SELLER, 0, T0.plusSeconds(1)))
                .isInstanceOf(EscrowException.class);
        assertThatThrownBy(() -> new MemberVote(BUYER, SELLER, 1, null))
                .isInstanceOf(EscrowException.class);
    }
}
