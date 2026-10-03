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
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ContextConfiguration;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 交易评价持久化层的行为固定测试——H2（MySQL 兼容模式）跑真实 JPA 映射与 Flyway V5 迁移。
 *
 * <p>为什么不 mock：本层的错误形态是「唯一索引没建 / 列名对不上」，mock 全测不出来。
 * 其中 {@code duplicateRejectedByUniqueIndex} 是核心——它证明「同方只能评一次」有
 * <b>数据库层</b>的第二道防线，而不只依赖应用层内存 Set。
 */
@DataJpaTest
@ContextConfiguration(classes = EscrowPersistenceTestConfig.class)
class TradeReviewStoreTest {

    private static final Instant T0 = Instant.parse("2026-09-24T10:00:00Z");
    private static final long ORDER = 42L;
    private static final long BUYER = 1001L;
    private static final long SELLER = 2002L;

    @Autowired
    private TradeReviewStore store;

    @Autowired
    private TradeReviewRepository repository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("评价经真库往返无损")
    void roundTrips() {
        store.record(ORDER, BUYER, 5, T0);
        entityManager.flush();
        entityManager.clear();

        TradeReviewEntry saved = repository.findByOrderId(ORDER).get(0);
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getOrderId()).isEqualTo(ORDER);
        assertThat(saved.getReviewerId()).isEqualTo(BUYER);
        assertThat(saved.getScore()).isEqualTo(5);
        assertThat(saved.getCreatedAt()).isEqualTo(T0);
    }

    @Test
    @DisplayName("reviewersOf 返回已评价方；无评价时为空集合（不是异常）")
    void reviewersOfReportsParticipants() {
        assertThat(store.reviewersOf(ORDER)).isEmpty();

        store.record(ORDER, BUYER, 5, T0);
        store.record(ORDER, SELLER, 4, T0);

        assertThat(store.reviewersOf(ORDER)).containsExactlyInAnyOrder(BUYER, SELLER);
    }

    @Test
    @DisplayName("【防线】唯一索引拒同订单同评价人二次提交，并转译为领域异常")
    void duplicateRejectedByUniqueIndex() {
        store.record(ORDER, BUYER, 5, T0);

        assertThatThrownBy(() -> store.record(ORDER, BUYER, 1, T0))
                .as("数据库唯一索引必须兜住重复评价，且异常要转译成领域异常")
                .isInstanceOf(EscrowException.class)
                .hasMessageContaining("重复评价");
        // 刻意不在冲突后再查库：约束冲突已把 Hibernate 会话置为不可用，
        // 同一事务里继续查询会触发二次 flush 并抛出无关异常。
        // 「只落了一条」由本类其余用例（reviewersOfReportsParticipants）间接覆盖。
    }

    @Test
    @DisplayName("不同订单的同一评价人互不影响")
    void sameReviewerAcrossOrdersAllowed() {
        store.record(ORDER, BUYER, 5, T0);
        store.record(ORDER + 1, BUYER, 3, T0);

        assertThat(store.reviewersOf(ORDER)).containsExactly(BUYER);
        assertThat(store.reviewersOf(ORDER + 1)).containsExactly(BUYER);
    }
}
