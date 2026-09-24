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
import java.util.HashSet;
import java.util.Set;

/**
 * 交易评价守卫（原文档 T18）：限定"何时能评、谁能评、评分范围、各评一次"。
 *
 * <h2>可评价的状态</h2>
 * <p>仅 {@code RELEASED}（已放款）与 {@code REFUNDED}（已退款）——两者都代表交易已了结。
 * <b>{@code CANCELLED} 刻意不可评</b>：订单被取消意味着没有实际履约，评价它只会污染信用数据。
 *
 * <h2>评分范围</h2>
 * <p>{@value #MIN_SCORE}–{@value #MAX_SCORE}。用整型而非其它量纲，便于与信用分模型对接。
 *
 * <p>本类<b>有状态</b>（记录已评价方），生命周期与一笔订单的结算阶段一致，非线程安全。
 */
public final class TradeReview {

    /** 最低评分。 */
    public static final int MIN_SCORE = 1;
    /** 最高评分。 */
    public static final int MAX_SCORE = 5;

    private static final Set<EscrowOrder.State> REVIEWABLE =
            EnumSet.of(EscrowOrder.State.RELEASED, EscrowOrder.State.REFUNDED);

    private final EscrowOrder.State state;
    private final long buyerId;
    private final long sellerId;
    private final Set<Long> reviewed = new HashSet<>();

    public TradeReview(EscrowOrder order) {
        if (order == null) {
            throw new EscrowException("交易评价：订单不可为空");
        }
        this.state = order.currentState();
        this.buyerId = order.getBuyerUserId();
        this.sellerId = order.getSellerUserId();
    }

    /**
     * 提交一条评价。
     *
     * <p>校验顺序：可评价状态 → 当事方 → 评分范围 → 未评价过。评分非法不会污染"已评价"记录。
     *
     * @param actorId 评价人（须为买卖双方之一）
     * @param score   评分（{@value #MIN_SCORE}–{@value #MAX_SCORE}）
     * @throws EscrowException 任一守卫不满足
     */
    public void submit(long actorId, int score) {
        requireReviewable();
        requireParty(actorId);
        requireScore(score);
        if (reviewed.contains(actorId)) {
            throw new EscrowException("交易评价：该方已评价过，不可重复评价");
        }
        reviewed.add(actorId);
    }

    /** 某方是否已评价。 */
    public boolean hasReviewed(long actorId) {
        return reviewed.contains(actorId);
    }

    private void requireReviewable() {
        if (!REVIEWABLE.contains(state)) {
            throw new EscrowException("交易评价：当前状态 " + state
                    + " 不可评价（仅已放款 / 已退款的交易可评）");
        }
    }

    private void requireParty(long actorId) {
        if (actorId != buyerId && actorId != sellerId) {
            throw new EscrowException("交易评价：仅买卖双方可评价");
        }
    }

    private void requireScore(int score) {
        if (score < MIN_SCORE || score > MAX_SCORE) {
            throw new EscrowException("交易评价：评分必须在 " + MIN_SCORE + "–" + MAX_SCORE
                    + "，实为 " + score);
        }
    }
}
