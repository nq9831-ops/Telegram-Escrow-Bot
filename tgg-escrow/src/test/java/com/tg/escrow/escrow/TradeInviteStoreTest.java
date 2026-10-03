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

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 邀请持久化层的行为固定测试——H2（MySQL 兼容模式）跑真实 JPA 映射与 Flyway V4 迁移。
 *
 * <p>为什么不 mock：本层的错误形态是「列名对不上 / 类型不匹配 / 唯一索引没建」，
 * mock 全测不出来，只会表现成生产启动即失败。用真库跑一遍 DDL 与实体映射是唯一能证明二者一致的方式。
 */
@DataJpaTest
@ContextConfiguration(classes = EscrowPersistenceTestConfig.class)
class TradeInviteStoreTest {

    private static final long BUYER = 1001L;
    private static final String TOKEN = "00112233445566778899aabbccddeeff";
    private static final Instant T0 = Instant.parse("2026-09-24T00:00:00Z");
    private static final Instant EXPIRES = T0.plus(Duration.ofHours(24));

    @Autowired
    private TradeInviteStore store;

    @Autowired
    private TradeInviteRepository repository;

    @Autowired
    private EntityManager entityManager;

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private TradeInvite newInvite() {
        return new TradeInvite(BUYER, new BigDecimal("100.00000000"), "USDT", TOKEN, T0, EXPIRES);
    }

    @Test
    @DisplayName("save 后回填主键，字段经真库往返无损，初始未被消费")
    void savePersistsAndReturnsId() {
        TradeInvite saved = store.save(newInvite());
        assertThat(saved.getId()).isNotNull();

        flushAndClear();
        TradeInvite reloaded = repository.findByToken(TOKEN).orElseThrow();

        assertThat(reloaded.getBuyerUserId()).isEqualTo(BUYER);
        assertThat(reloaded.getAmount()).isEqualByComparingTo("100.00000000");
        assertThat(reloaded.getCurrency()).isEqualTo("USDT");
        assertThat(reloaded.getCreatedAt()).isEqualTo(T0);
        assertThat(reloaded.getExpiresAt()).isEqualTo(EXPIRES);
        assertThat(reloaded.isConsumed()).isFalse();
        assertThat(reloaded.getAcceptedOrderId()).isNull();
    }

    @Test
    @DisplayName("按令牌查回；未知令牌返回空（不是异常）")
    void byTokenFindsAndMissesAreEmpty() {
        store.save(newInvite());
        flushAndClear();

        assertThat(store.byToken(TOKEN)).isPresent();
        assertThat(store.byToken("no-such-token")).isEmpty();
        assertThat(store.byToken("   ")).isEmpty();
        assertThat(store.byToken(null)).isEmpty();
    }

    @Test
    @DisplayName("消费可持久化——写回 acceptedOrderId 后重读为已消费")
    void consumptionPersists() {
        TradeInvite invite = store.save(newInvite());
        invite.markAccepted(4242L);
        store.save(invite);

        flushAndClear();
        TradeInvite reloaded = store.byToken(TOKEN).orElseThrow();
        assertThat(reloaded.isConsumed()).isTrue();
        assertThat(reloaded.getAcceptedOrderId()).isEqualTo(4242L);
    }

    @Test
    @DisplayName("令牌唯一索引生效——同令牌二次插入即失败（不可枚举的定位键必须唯一）")
    void duplicateTokenIsRejectedByUniqueIndex() {
        store.save(newInvite());
        flushAndClear();

        assertThatThrownBy(() -> {
            store.save(new TradeInvite(2002L, new BigDecimal("5"), "TON", TOKEN, T0, EXPIRES));
            entityManager.flush();
        }).isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("save 传 null → 抛异常（端口契约：不得静默接受）")
    void saveNullFailsClosed() {
        assertThatThrownBy(() -> store.save(null)).isInstanceOf(EscrowException.class);
    }
}
