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

import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@link TradeReviewStore} 的 JPA 实现——读写 {@code trade_reviews} 表。
 *
 * <h2>为什么用 {@code saveAndFlush} 而不是 {@code save}</h2>
 * <p>唯一索引 {@code (order_id, reviewer_id)} 的冲突要在<b>本方法内</b>暴露，才能被转译成
 * 领域异常。{@code save} 会把 INSERT 推迟到事务提交，届时异常从调用方之外抛出，
 * 本方法就抓不到——那会让"重复评价"以原始的技术异常形态泄漏到用户面前。
 */
public final class JpaTradeReviewStore implements TradeReviewStore {

    private final TradeReviewRepository repository;

    public JpaTradeReviewStore(TradeReviewRepository repository) {
        if (repository == null) {
            throw new EscrowException("交易评价存储：未提供仓储");
        }
        this.repository = repository;
    }

    @Override
    public void record(long orderId, long reviewerId, int score, Instant createdAt) {
        try {
            repository.saveAndFlush(new TradeReviewEntry(orderId, reviewerId, score, createdAt));
        } catch (DataIntegrityViolationException ex) {
            // 唯一索引兜底：该方对本单已评价过
            throw new EscrowException("交易评价：该方已评价过本单，不可重复评价", ex);
        }
    }

    @Override
    public Set<Long> reviewersOf(long orderId) {
        return repository.findByOrderId(orderId).stream()
                .map(TradeReviewEntry::getReviewerId)
                .collect(Collectors.toUnmodifiableSet());
    }
}
