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

import java.time.Clock;

/**
 * 交易评价服务——把「评价」这条命令接到 {@link TradeReview} 守卫与持久化上（Wave 3 接线）。
 *
 * <h2>流程</h2>
 * <pre>
 * review(order, actorId, score)
 *   ├─ 从 {@link TradeReviewStore} 取该单已评价方 → 重建 TradeReview（含既有事实）
 *   ├─ TradeReview.submit 守卫：仅终态可评 / 仅当事人 / 评分 1-5 / 该方未评过
 *   └─ 落库（唯一索引再兜一道）
 * </pre>
 *
 * <h2>为什么不并进 EscrowTradeService</h2>
 * <p>{@link EscrowTradeService} 管的是<b>订单状态机</b>的推进（每次调用都改订单）；
 * 评价<b>不改订单状态</b>，写的是另一个聚合（评价记录）。并进去会让那个类同时承担
 * 两件不同的事，也会给所有既有调用点强加一个它们不需要的依赖。
 */
public final class TradeReviewService {

    private final TradeReviewStore store;
    private final Clock clock;

    public TradeReviewService(TradeReviewStore store, Clock clock) {
        if (store == null) {
            throw new EscrowException("交易评价：未提供评价存储");
        }
        if (clock == null) {
            throw new EscrowException("交易评价：未提供时钟");
        }
        this.store = store;
        this.clock = clock;
    }

    /**
     * 提交一条评价。
     *
     * @param order   被评价的订单（须已落库、且处于终态）
     * @param actorId 评价人（须为买卖双方之一）
     * @param score   评分（1–5）
     * @throws EscrowException 订单未落库 / 状态不可评 / 非当事人 / 评分越界 / 已评价过
     */
    public void review(EscrowOrder order, long actorId, int score) {
        if (order == null) {
            throw new EscrowException("交易评价：未提供订单");
        }
        if (order.getId() == null) {
            throw new EscrowException("交易评价：订单尚未落库，无法评价");
        }
        // 从库里重建"谁评过"——这样重启进程也不会丢掉这条事实
        TradeReview review = new TradeReview(order, store.reviewersOf(order.getId()));
        review.submit(actorId, score);
        store.record(order.getId(), actorId, score, clock.instant());
    }
}
