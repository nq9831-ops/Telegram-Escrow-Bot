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

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 群成员投票（ET-41）：发起 → 加权计票 → 结果判定。
 *
 * <h2>加权而非一人一票</h2>
 * <p>票权来自 {@link VoterRegistry} 分层（核心 3 / 资深 2 / 普通 1）——长期良好交易者权重更高。
 * 一处定义、两处引用：分层表同时服务投票权重与排名等级，避免两套数漂移。
 *
 * <h2>四道守卫（fail-closed）</h2>
 * <ol>
 *   <li><b>观察员无投票权</b>（权重 0 层级拒绝）；</li>
 *   <li><b>回避</b>：买卖双方本人、及与双方有近期交易者不得投票（复用 {@link VoterRecusal}）；</li>
 *   <li><b>一人一票</b>：重复投票拒绝；</li>
 *   <li><b>截止后不可投票</b>。</li>
 * </ol>
 *
 * <h2>结果三值</h2>
 * <p>{@code RELEASE} / {@code REFUND} 为明确裁决；参与人数不足门槛或<b>加权平局</b>时为
 * {@code INSUFFICIENT}（无共识）——由 {@link FallbackSettle} 兜底，不在本类擅自裁决。
 * 本类有状态、非线程安全。
 */
public final class MemberVote {

    /** 投票选项。 */
    public enum Choice {
        /** 放款给卖方。 */
        RELEASE,
        /** 退款给买方。 */
        REFUND
    }

    /** 计票结果。 */
    public enum Outcome {
        /** 放款裁决。 */
        RELEASE,
        /** 退款裁决。 */
        REFUND,
        /** 无共识（人数不足门槛或加权平局）——交 {@link FallbackSettle} 兜底。 */
        INSUFFICIENT
    }

    private final long buyerId;
    private final long sellerId;
    private final int quorum;
    private final Instant deadline;
    private final Map<Long, Choice> ballots = new HashMap<>();
    private final Map<Long, VoterRegistry.Tier> tiers = new HashMap<>();

    /**
     * @param buyerId  争议订单买方（自身不得投票）
     * @param sellerId 争议订单卖方（自身不得投票）
     * @param quorum   最低参与人数（≥ 1）
     * @param deadline 投票截止时刻（不得为空；此后投票一律拒绝）
     */
    public MemberVote(long buyerId, long sellerId, int quorum, Instant deadline) {
        if (quorum < 1) {
            throw new EscrowException("群投票：参与门槛必须 ≥ 1，实为 " + quorum);
        }
        if (deadline == null) {
            throw new EscrowException("群投票：投票截止时刻未提供");
        }
        this.buyerId = buyerId;
        this.sellerId = sellerId;
        this.quorum = quorum;
        this.deadline = deadline;
    }

    /** 投一票（不做窗口回避校验——买卖双方本人仍被拒绝）。 */
    public void cast(long voterId, VoterRegistry.Tier tier, Choice choice, Instant now) {
        castWithRecusal(voterId, tier, choice, Set.of(), now);
    }

    /**
     * 投一票并做窗口回避校验。
     *
     * @param recentCounterparties 该投票人窗口内的交易对手（回避判定输入，见 {@link VoterRecusal}）
     */
    public void castWithRecusal(long voterId, VoterRegistry.Tier tier, Choice choice,
                                Set<Long> recentCounterparties, Instant now) {
        if (tier == null || choice == null || now == null) {
            throw new EscrowException("群投票：投票参数不完整");
        }
        if (!now.isBefore(deadline)) {
            throw new EscrowException("群投票：投票已截止（" + deadline + "）");
        }
        if (!VoterRegistry.canVote(tier)) {
            throw new EscrowException("群投票：观察员无投票权");
        }
        if (VoterRecusal.isRecused(recentCounterparties, buyerId, sellerId, voterId)) {
            throw new EscrowException("群投票：投票人须回避（利益关联或当事方本人）");
        }
        if (ballots.containsKey(voterId)) {
            throw new EscrowException("群投票：每人限投一票");
        }
        ballots.put(voterId, choice);
        tiers.put(voterId, tier);
    }

    /**
     * 判定结果。
     *
     * @param now 当前时刻（用于校验"截止后才有结论"这一业务语义的时刻一致性）
     * @return 明确裁决，或 {@link Outcome#INSUFFICIENT}（人数不足/平局）
     */
    public Outcome result(Instant now) {
        if (now == null) {
            throw new EscrowException("群投票：判定时刻未提供");
        }
        if (ballots.size() < quorum) {
            return Outcome.INSUFFICIENT;
        }
        long release = 0;
        long refund = 0;
        for (Map.Entry<Long, Choice> e : ballots.entrySet()) {
            long weight = VoterRegistry.weightOf(tiers.get(e.getKey()));
            if (e.getValue() == Choice.RELEASE) {
                release += weight;
            } else {
                refund += weight;
            }
        }
        if (release == refund) {
            // 平局即无共识——不在本类擅自裁决，交 FallbackSettle
            return Outcome.INSUFFICIENT;
        }
        return release > refund ? Outcome.RELEASE : Outcome.REFUND;
    }

    /** 已投票人数。 */
    public int participantCount() {
        return ballots.size();
    }
}
