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
import com.tg.escrow.moderation.WarningCountQuery;

import java.time.Clock;
import java.util.EnumSet;

/**
 * 惩罚编排（ET-61）——把 {@link PenaltyPlanner} 的两个入参真正接上，并记录争议败诉。
 *
 * <h2>接的是什么</h2>
 * <p>{@link PenaltyPlanner} 是纯函数，早就写好但<b>零引用</b>：它要的三个入参（累计警告、
 * 争议败诉数、可疑标记）中，后两个此前<b>没有任何来源</b>。本服务补上：
 * <ul>
 *   <li><b>争议败诉数</b> ← 新表 {@code dispute_losses}（{@link #recordDisputeLoss} 在裁决落定时记一行）；</li>
 *   <li><b>累计警告</b> ← {@link WarningCountQuery}（跨群累加）；</li>
 *   <li><b>可疑标记</b> ← 由调用方显式传入（本层不自取——它来自评估链，不是本层的账）。</li>
 * </ul>
 *
 * <h2>败诉方口径（单点定义）</h2>
 * <p>见 {@link #loserOf}：裁决 outcome <b>对其不利</b>的一方即败诉方。全项目只此一处。
 *
 * <h2>VOTE_SUSPEND 维度</h2>
 * <p>{@link PenaltyPlanner.Penalty#VOTE_SUSPEND} 在本部署下<b>无对象</b>——群投票裁决层已被拍板
 * 不引入（方案 A）。计划里仍会返回它（纯函数照算），但没有任何消费者会去"暂停票权"。
 * 这是如实的状态，不是遗漏。
 *
 * <p>时钟由构造注入，使败诉记录时刻可测。
 */
public final class PenaltyService implements PenaltyQuery {

    private final DisputeLossStore losses;
    private final WarningCountQuery warnings;
    private final Clock clock;

    public PenaltyService(DisputeLossStore losses, WarningCountQuery warnings, Clock clock) {
        if (losses == null) {
            throw new EscrowException("惩罚编排：未提供败诉台账存储");
        }
        if (warnings == null) {
            throw new EscrowException("惩罚编排：未提供警告计数端口");
        }
        if (clock == null) {
            throw new EscrowException("惩罚编排：未提供时钟");
        }
        this.losses = losses;
        this.warnings = warnings;
        this.clock = clock;
    }

    /**
     * 记录一次争议败诉（幂等：同一订单已有记录则原样返回，不重复记罚）。
     *
     * @param order   裁决后的订单（须已落库）
     * @param outcome 裁决结果——决定谁是败诉方（{@link #loserOf}）
     */
    public DisputeLoss recordDisputeLoss(EscrowOrder order, EscrowVerdict.Outcome outcome) {
        if (order == null) {
            throw new EscrowException("惩罚编排：未提供订单");
        }
        Long orderId = order.getId();
        if (orderId == null) {
            throw new EscrowException("惩罚编排：订单尚未落库（无订单号）");
        }
        if (outcome == null) {
            throw new EscrowException("惩罚编排：裁决结果未提供");
        }
        return losses.find(orderId).orElseGet(
                () -> losses.save(new DisputeLoss(orderId, loserOf(order, outcome), clock.instant())));
    }

    /**
     * 败诉方 = 裁决 outcome 对其不利的一方（用户 2026-10-02 拍板口径）。
     *
     * <p>{@code RELEASE}（放款卖方）→ 卖方胜、<b>买方败</b>；否则（{@code REFUND}，退款买方）→
     * 买方胜、<b>卖方败</b>。全项目唯一定义处。
     */
    public static long loserOf(EscrowOrder order, EscrowVerdict.Outcome outcome) {
        if (order == null) {
            throw new EscrowException("惩罚编排：未提供订单");
        }
        if (outcome == null) {
            throw new EscrowException("惩罚编排：裁决结果未提供");
        }
        return outcome == EscrowVerdict.Outcome.RELEASE
                ? order.getBuyerUserId()
                : order.getSellerUserId();
    }

    /** 某人名下的败诉次数。 */
    public int lossesOf(long userId) {
        return losses.countByLoser(userId);
    }

    /**
     * 某人的惩罚计划（{@link PenaltyQuery} 的实现）——可疑标记按"未标记"处理。
     *
     * <p>可疑标记不在此自取：它来自评估链（`FraudLinkage`）的即时判定，不是一份可随时查的账。
     * 需要按可疑标记罚的调用方走 {@link #plan(int, int, boolean)} 显式传入。
     */
    @Override
    public EnumSet<PenaltyPlanner.Penalty> planOf(long userId) {
        return PenaltyPlanner.plan(warnings.warnCountOf(userId), lossesOf(userId), false);
    }

    /** 带全部三个入参的计划（供能提供"可疑标记"的调用方使用）。 */
    public EnumSet<PenaltyPlanner.Penalty> plan(int warnCount, int disputeLosses, boolean suspicious) {
        return PenaltyPlanner.plan(warnCount, disputeLosses, suspicious);
    }
}
