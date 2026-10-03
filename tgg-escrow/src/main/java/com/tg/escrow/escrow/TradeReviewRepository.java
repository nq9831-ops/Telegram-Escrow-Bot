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

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 交易评价的 Spring Data 仓储。
 *
 * <p>「谁评过」是接评价命令时唯一需要的读路径；此外本仓储还为**信用读模型**提供
 * 「某用户<b>收到</b>了多少评价 / 其中多少是好评」两条聚合。
 *
 * <h2>为什么"收到"要跨表</h2>
 * <p>{@code trade_reviews} 只记 {@code (order_id, reviewer_id, score)}——<b>没有"被评人"列</b>。
 * 因此"某人收到的好评"必须回到订单表上取另一侧当事人：
 * 评价写在"I 参与的订单"上、且评价人不是 I，则该评价是<b>写给 I</b> 的。这也顺带排除了
 * 自评（同一 ID 同时是买卖双方本就非法，这里再加一道 {@code reviewerId <> userId} 的兜底）。
 */
public interface TradeReviewRepository extends JpaRepository<TradeReviewEntry, Long> {

    /** 取某订单的全部评价（无则空列表）。 */
    List<TradeReviewEntry> findByOrderId(long orderId);

    /** 某用户<b>收到</b>的评价总数（其参与订单上、由对方写下的评价）。 */
    @Query("""
            select count(r) from TradeReviewEntry r, EscrowOrder o
            where r.orderId = o.id
              and (o.buyerUserId = :userId or o.sellerUserId = :userId)
              and r.reviewerId <> :userId
            """)
    long countReceivedByUser(@Param("userId") long userId);

    /**
     * 某用户收到的好评数（{@code score >= :minScore}）。
     *
     * <p>阈值从调用方传入而非写死在这里：好评线的定义属口径，口径只该有一处
     * （见 {@code TraderCreditService}），仓储不该偷偷持有第二份。
     */
    @Query("""
            select count(r) from TradeReviewEntry r, EscrowOrder o
            where r.orderId = o.id
              and (o.buyerUserId = :userId or o.sellerUserId = :userId)
              and r.reviewerId <> :userId
              and r.score >= :minScore
            """)
    long countReceivedPositiveByUser(@Param("userId") long userId, @Param("minScore") int minScore);
}
