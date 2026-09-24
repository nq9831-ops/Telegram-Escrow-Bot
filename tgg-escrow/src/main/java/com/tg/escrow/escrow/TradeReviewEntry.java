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

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 一条交易评价（表 {@code trade_reviews}）——把 {@link TradeReview} 的内存事实变成可持久化的事实。
 *
 * <h2>为什么需要它</h2>
 * <p>{@link TradeReview} 靠内部 Set 记住"谁评过"，进程一重启就忘——同一方可以重复刷评价。
 * 本实体负责把这条事实落库；"各评一次"在数据库层由 {@code (order_id, reviewer_id)} 唯一索引
 * 再兜一道，两条防线任一失效都不会产生脏数据。
 *
 * <p>只读语义 + 不可变：评价一旦提交不修改、不删除（更正属于独立需求，需审计，不在本波）。
 */
@Entity
@Table(name = "trade_reviews")
public class TradeReviewEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private long orderId;

    @Column(name = "reviewer_id", nullable = false)
    private long reviewerId;

    @Column(name = "score", nullable = false)
    private int score;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** JPA 要求的无参构造。业务代码请用公开构造器。 */
    protected TradeReviewEntry() {
    }

    /**
     * @param orderId    被评价的订单号
     * @param reviewerId 评价人（须为买卖双方之一，由 {@link TradeReview} 守卫）
     * @param score      评分（1–5，由 {@link TradeReview} 守卫）
     * @param createdAt  评价时刻
     */
    public TradeReviewEntry(long orderId, long reviewerId, int score, Instant createdAt) {
        if (orderId <= 0) {
            throw new EscrowException("交易评价：订单号非法（" + orderId + "）");
        }
        if (createdAt == null) {
            throw new EscrowException("交易评价：评价时刻未提供");
        }
        this.orderId = orderId;
        this.reviewerId = reviewerId;
        this.score = score;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public long getOrderId() {
        return orderId;
    }

    public long getReviewerId() {
        return reviewerId;
    }

    public int getScore() {
        return score;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
