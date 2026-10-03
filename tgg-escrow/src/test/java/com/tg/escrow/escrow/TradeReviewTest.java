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
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 交易评价（T18）的行为固定测试。
 *
 * <p>三条守卫各自独立：<b>仅已完成交易可评</b>（未结算无评价对象）、
 * <b>双方各一次</b>（防刷评价）、<b>评分在 1–5</b>。取消订单刻意不可评——
 * 没有实际履约，评价会污染信用数据。
 */
class TradeReviewTest {

    private static final long BUYER = 1001L;
    private static final long SELLER = 2002L;
    private static final long STRANGER = 9999L;
    private static final Instant T0 = Instant.parse("2026-09-23T00:00:00Z");

    private EscrowOrder releasedOrder() {
        EscrowOrder o = new EscrowOrder(BUYER, SELLER, new BigDecimal("100"), "USDT", T0);
        o.markLocked(T0);
        o.markReleased(T0);
        return o;
    }

    @Test
    @DisplayName("已完成交易，买卖双方各可评一次")
    void bothPartiesCanReviewOnce() {
        TradeReview review = new TradeReview(releasedOrder());

        review.submit(BUYER, 5);
        review.submit(SELLER, 4);

        assertThat(review.hasReviewed(BUYER)).isTrue();
        assertThat(review.hasReviewed(SELLER)).isTrue();
    }

    @Test
    @DisplayName("同一方评两次 → 拒绝")
    void samePartyCannotReviewTwice() {
        TradeReview review = new TradeReview(releasedOrder());
        review.submit(BUYER, 5);

        assertThatThrownBy(() -> review.submit(BUYER, 1))
                .isInstanceOf(EscrowException.class);
    }

    @Test
    @DisplayName("未结算的交易不可评价")
    void cannotReviewBeforeSettlement() {
        EscrowOrder o = new EscrowOrder(BUYER, SELLER, new BigDecimal("100"), "USDT", T0);
        o.markLocked(T0);

        assertThatThrownBy(() -> new TradeReview(o).submit(BUYER, 5))
                .isInstanceOf(EscrowException.class);
    }

    @Test
    @DisplayName("退款完成的交易可评价")
    void refundedOrderCanBeReviewed() {
        EscrowOrder o = new EscrowOrder(BUYER, SELLER, new BigDecimal("100"), "USDT", T0);
        o.markLocked(T0);
        o.markRefunded("争议裁决", T0);

        new TradeReview(o).submit(BUYER, 3); // 不抛即通过
    }

    @Test
    @DisplayName("取消订单不可评价——无实际履约")
    void cancelledOrderCannotBeReviewed() {
        EscrowOrder o = new EscrowOrder(BUYER, SELLER, new BigDecimal("100"), "USDT", T0);
        o.markCancelled("买家反悔", T0);

        assertThatThrownBy(() -> new TradeReview(o).submit(BUYER, 5))
                .isInstanceOf(EscrowException.class);
    }

    @Test
    @DisplayName("非当事方不得评价")
    void strangerCannotReview() {
        assertThatThrownBy(() -> new TradeReview(releasedOrder()).submit(STRANGER, 5))
                .isInstanceOf(EscrowException.class);
    }

    @Test
    @DisplayName("评分超出 1–5 → 拒绝")
    void scoreOutOfRangeRejected() {
        TradeReview review = new TradeReview(releasedOrder());

        assertThatThrownBy(() -> review.submit(BUYER, 0)).isInstanceOf(EscrowException.class);
        assertThatThrownBy(() -> review.submit(BUYER, 6)).isInstanceOf(EscrowException.class);
    }
}
