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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 交易评价服务的行为固定测试（Wave 3 接线）。
 *
 * <p>重点不在"评一次能不能过"，而在<b>重复评价必须被拒——且跨服务实例仍被拒</b>：
 * 那一条证明「谁评过」来自存储而非进程内存。若实现只靠内存 Set，重建实例后就会放行。
 */
class TradeReviewServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-24T10:00:00Z");
    private static final Clock FIXED = Clock.fixed(NOW, ZoneOffset.UTC);

    private static final long BUYER = 1001L;
    private static final long SELLER = 2002L;
    private static final long STRANGER = 9999L;
    private static final BigDecimal AMOUNT = new BigDecimal("100.00");

    /** 内存评价存储：重复评价抛异常，模拟唯一索引。 */
    static final class InMemoryReviewStore implements TradeReviewStore {
        private final Map<Long, Set<Long>> byOrder = new HashMap<>();

        @Override
        public void record(long orderId, long reviewerId, int score, Instant createdAt) {
            Set<Long> reviewers = byOrder.computeIfAbsent(orderId, k -> new HashSet<>());
            if (!reviewers.add(reviewerId)) {
                throw new EscrowException("交易评价：该方已评价过本单，不可重复评价");
            }
        }

        @Override
        public Set<Long> reviewersOf(long orderId) {
            return Set.copyOf(byOrder.getOrDefault(orderId, Set.of()));
        }
    }

    private static TradeReviewService service(TradeReviewStore store) {
        return new TradeReviewService(store, FIXED);
    }

    /** 一笔已放款（终态）且已落库的订单。 */
    private static EscrowOrder releasedOrder() {
        EscrowOrder order = new EscrowOrder(BUYER, SELLER, AMOUNT, "USDT", NOW);
        order.markLocked(NOW);
        order.markDelivered(NOW);
        order.markReleased(NOW);
        order.assignId(1L);
        return order;
    }

    @Test
    @DisplayName("终态订单：当事人评价 → 落库成功")
    void acceptsReviewOnTerminalOrder() {
        InMemoryReviewStore store = new InMemoryReviewStore();

        service(store).review(releasedOrder(), BUYER, 5);

        assertThat(store.reviewersOf(1L)).containsExactly(BUYER);
    }

    @Test
    @DisplayName("未到终态（OPEN）评价 → 拒（评价污染信用数据）")
    void rejectsReviewBeforeTerminal() {
        EscrowOrder order = new EscrowOrder(BUYER, SELLER, AMOUNT, "USDT", NOW);
        order.assignId(2L);

        assertThatThrownBy(() -> service(new InMemoryReviewStore()).review(order, BUYER, 5))
                .isInstanceOf(EscrowException.class);
    }

    @Test
    @DisplayName("第三方评价 → 拒")
    void rejectsStranger() {
        assertThatThrownBy(() -> service(new InMemoryReviewStore()).review(releasedOrder(), STRANGER, 5))
                .isInstanceOf(EscrowException.class);
    }

    @Test
    @DisplayName("评分越界（0 / 6）→ 拒")
    void rejectsScoreOutOfRange() {
        assertThatThrownBy(() -> service(new InMemoryReviewStore()).review(releasedOrder(), BUYER, 0))
                .isInstanceOf(EscrowException.class);
        assertThatThrownBy(() -> service(new InMemoryReviewStore()).review(releasedOrder(), BUYER, 6))
                .isInstanceOf(EscrowException.class);
    }

    @Test
    @DisplayName("【反证】重复评价被拒——且换成【另一个服务实例】仍被拒（证明取自存储而非内存）")
    void duplicateRejectedAcrossInstances() {
        InMemoryReviewStore store = new InMemoryReviewStore();
        service(store).review(releasedOrder(), BUYER, 5);

        // 新实例 = 模拟进程重启后内存 Set 为空
        assertThatThrownBy(() -> service(store).review(releasedOrder(), BUYER, 1))
                .as("若实现只靠进程内存，这一条会放行——正是本用例要证伪的")
                .isInstanceOf(EscrowException.class);
        assertThat(store.reviewersOf(1L)).containsExactly(BUYER);
    }

    @Test
    @DisplayName("买卖双方各可评一次（卖方评不冲突）")
    void bothPartiesCanReviewOnce() {
        InMemoryReviewStore store = new InMemoryReviewStore();

        service(store).review(releasedOrder(), BUYER, 5);
        service(store).review(releasedOrder(), SELLER, 4);

        assertThat(store.reviewersOf(1L)).containsExactlyInAnyOrder(BUYER, SELLER);
    }

    @Test
    @DisplayName("订单未落库（无 id）→ 拒（否则会写出无主键归属的评价）")
    void rejectsUnsavedOrder() {
        EscrowOrder order = new EscrowOrder(BUYER, SELLER, AMOUNT, "USDT", NOW);
        order.markLocked(NOW);
        order.markDelivered(NOW);
        order.markReleased(NOW);

        assertThatThrownBy(() -> service(new InMemoryReviewStore()).review(order, BUYER, 5))
                .isInstanceOf(EscrowException.class);
    }

    @Test
    @DisplayName("构造：依赖缺失 → 抛")
    void missingDepsFailFast() {
        assertThatThrownBy(() -> new TradeReviewService(null, FIXED))
                .isInstanceOf(EscrowException.class);
        assertThatThrownBy(() -> new TradeReviewService(new InMemoryReviewStore(), null))
                .isInstanceOf(EscrowException.class);
    }
}
