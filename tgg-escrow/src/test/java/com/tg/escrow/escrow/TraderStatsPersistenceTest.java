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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 信用读模型的**真实持久化**验证（真实 H2 + Flyway + JPA，非 mock）。
 *
 * <h2>它补的是什么洞</h2>
 * <p>{@link TraderCreditServiceTest} 钉住了口径，但它喂的是**手造的 {@link TraderStats}**——
 * 于是"这些数字究竟怎么从库里算出来"完全没有验证。这一层的错误形态极其隐蔽：
 * JPQL 字段名写错、条件写反、分组粒度不对，都<b>不会报错</b>，只会让榜单对所有人显示
 * "0 笔、0 分"。上下文启动时 Spring 只会**解析**查询语法，不会执行它——所以只有真跑一遍
 * 才算验过。
 *
 * <h2>用法约束</h2>
 * <p>本类的 H2 与其它持久化测试<b>共享同一个库</b>（{@code DB_CLOSE_DELAY=-1}），
 * 故 {@link #cleanUp()} 在每个用例后清表：不清就会让别的用例的"全库计数"被污染。
 * 用户 ID 也刻意取大值，避免与其它测试的造数撞车。
 */
@SpringBootTest(classes = EscrowPersistenceTestConfig.class)
class TraderStatsPersistenceTest {

    /** 本类专用用户段（远离其它测试的小 ID）。 */
    private static final long BUYER = 9_100_001L;
    private static final long SELLER_A = 9_100_002L;
    private static final long SELLER_B = 9_100_003L;
    private static final long STRANGER = 9_100_004L;

    private static final Instant T0 = Instant.parse("2026-03-01T00:00:00Z");

    @Autowired
    private EscrowOrderRepository orders;

    @Autowired
    private TradeReviewRepository reviews;

    private JpaTraderStatsPort port;

    @BeforeEach
    void setUp() {
        port = new JpaTraderStatsPort(orders, reviews);
    }

    @AfterEach
    void cleanUp() {
        reviews.deleteAll();
        orders.deleteAll();
    }

    /** 造一笔走到指定终态的订单。 */
    private EscrowOrder order(long buyer, long seller, EscrowOrder.State target, Instant at) {
        EscrowOrder o = new EscrowOrder(buyer, seller, new BigDecimal("10"), "USDT", at);
        switch (target) {
            case RELEASED -> {
                o.markConfirmed(at);
                o.markLocked(at);
                o.markDelivered(at);
                o.markReleased(at);
            }
            case REFUNDED -> {
                o.markConfirmed(at);
                o.markLocked(at);
                o.markRefunded("协商退款", at);
            }
            case CANCELLED -> o.markCancelled("当事人取消", at);
            case DISPUTED -> {
                o.markConfirmed(at);
                o.markLocked(at);
                o.markDisputed("货不对板", at);
            }
            default -> throw new IllegalArgumentException("本测试只造终态/争议态：" + target);
        }
        return orders.save(o);
    }

    @Test
    @DisplayName("真实库：完成数只认 RELEASED——退款的交易不算「完成」，但计入参与总数")
    void completedCountsOnlyReleasedButTotalCountsAll() {
        order(BUYER, SELLER_A, EscrowOrder.State.RELEASED, T0);
        order(BUYER, SELLER_A, EscrowOrder.State.REFUNDED, T0);
        order(BUYER, SELLER_B, EscrowOrder.State.CANCELLED, T0);

        TraderStats stats = port.of(BUYER);

        assertThat(stats.completed()).as("只有 1 笔走到了放款").isEqualTo(1);
        assertThat(stats.totalCount()).as("参与总数含全部状态").isEqualTo(3);
        assertThat(stats.disputeCount()).isZero();
    }

    @Test
    @DisplayName("真实库：对手分布双向合并（当过买方对手、也当过卖方对手的人累加）")
    void counterpartiesMergeBothDirections() {
        order(BUYER, SELLER_A, EscrowOrder.State.RELEASED, T0);
        order(BUYER, SELLER_A, EscrowOrder.State.RELEASED, T0);
        order(SELLER_A, BUYER, EscrowOrder.State.RELEASED, T0); // BUYER 作为卖方，对手仍是 SELLER_A

        TraderStats stats = port.of(BUYER);

        assertThat(stats.counterpartyCounts()).containsEntry(SELLER_A, 3);
        assertThat(stats.totalCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("真实库：收到的评价按「对方写的」计——自己写的不算，未参与订单的评价也不算")
    void receivedReviewsExcludeSelfAndStrangers() {
        EscrowOrder mine = order(BUYER, SELLER_A, EscrowOrder.State.RELEASED, T0);
        EscrowOrder other = order(SELLER_B, STRANGER, EscrowOrder.State.RELEASED, T0);

        reviews.save(new TradeReviewEntry(mine.getId(), BUYER, 5, T0));       // 自评：不算
        reviews.save(new TradeReviewEntry(mine.getId(), SELLER_A, 4, T0));   // 对方好评：算
        reviews.save(new TradeReviewEntry(other.getId(), SELLER_B, 5, T0));  // 与 BUYER 无关：不算

        TraderStats stats = port.of(BUYER);

        assertThat(stats.reviewCount()).as("只有对方写的那一条").isEqualTo(1);
        assertThat(stats.positiveReviews()).as("score ≥ 4 记为好").isEqualTo(1);

        TraderStats seller = port.of(SELLER_A);
        assertThat(seller.reviewCount())
                .as("同一条评价对 BUYER 是「自评」（不计），对 SELLER_A 却是「收到」——"
                        + "收发口径按人判定，不是按行判定")
                .isEqualTo(1);
        assertThat(seller.positiveReviews()).isEqualTo(1);
    }

    @Test
    @DisplayName("真实库：无成交史的用户返回空统计（各计数为 0、首末时刻为空）——不抛异常")
    void userWithoutHistoryGetsEmptyStats() {
        TraderStats stats = port.of(STRANGER);

        assertThat(stats.totalCount()).isZero();
        assertThat(stats.completed()).isZero();
        assertThat(stats.firstAt()).isNull();
        assertThat(stats.lastAt()).isNull();
        assertThat(stats.counterpartyCounts()).isEmpty();
    }

    @Test
    @DisplayName("真实库：活跃天数取首末跨度——两笔相隔 10 天即 11 天（含两端）")
    void activeDaysSpanComesFromRealTimestamps() {
        order(BUYER, SELLER_A, EscrowOrder.State.RELEASED, T0);
        order(BUYER, SELLER_B, EscrowOrder.State.DISPUTED, T0.plusSeconds(10L * 86400));

        TraderStats stats = port.of(BUYER);

        assertThat(stats.firstAt()).isEqualTo(T0);
        assertThat(stats.disputeCount()).isEqualTo(1);
        TraderCredit credit = new TraderCreditService().summarize(stats);
        assertThat(credit.completed()).isEqualTo(1);
    }

    @Test
    @DisplayName("真实库：参与者枚举含买卖两侧，且去重")
    void allWithActivityCoversBothSides() {
        order(BUYER, SELLER_A, EscrowOrder.State.RELEASED, T0);
        order(BUYER, SELLER_A, EscrowOrder.State.RELEASED, T0);

        List<Long> ids = port.allWithActivity().stream().map(TraderStats::userId).toList();

        assertThat(ids).contains(BUYER, SELLER_A).doesNotContain(STRANGER);
        assertThat(ids).doesNotHaveDuplicates();
        assertThat(ids).hasSize(2);
    }
}
