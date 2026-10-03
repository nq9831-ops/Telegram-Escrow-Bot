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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TON Pay 支付引用持久化（V13）：登记幂等、解析、结算一次。
 *
 * <p>本类同时是 V13 迁移在 H2 MODE=MySQL 下的兼容性证明（flyway 真跑 + ddl-auto=validate）。
 */
@SpringBootTest(classes = EscrowPersistenceTestConfig.class)
class TonPayReferencePersistenceTest {

    /** 本类专用订单段（远离其它测试）。 */
    private static final long ORDER_A = 9_400_001L;

    @Autowired
    private TonPayReferencePort store;

    @Test
    @DisplayName("登记与解析：reference → 订单；重复登记幂等（先登记者为准）")
    void registerResolvesAndIsIdempotent() {
        store.register(ORDER_A, "ref-persist-1", "hash-x");
        store.register(ORDER_A + 1, "ref-persist-1", "hash-y");   // 重复登记应被忽略

        assertThat(store.resolveOrderId("ref-persist-1")).contains(ORDER_A);
        assertThat(store.resolveOrderId("ref-unknown")).isEmpty();
        assertThat(store.isSettled("ref-unknown")).as("未登记不是已结算").isFalse();
    }

    @Test
    @DisplayName("结算定格一次：markSettled 后 isSettled=true；重放（再次 markSettled）不覆盖首笔痕迹")
    void settleHappensOnce() {
        store.register(ORDER_A, "ref-persist-2", null);
        store.markSettled("ref-persist-2", "tx-first", new BigDecimal("100"), "USDT",
                Instant.parse("2026-10-03T00:00:00Z"));
        store.markSettled("ref-persist-2", "tx-replay", new BigDecimal("999"), "TON",
                Instant.parse("2026-10-03T01:00:00Z"));

        assertThat(store.isSettled("ref-persist-2")).isTrue();
        // 覆写必须不发生——通过仓储读原值验证
        TonPayReferenceRow row = repository().findByReference("ref-persist-2").orElseThrow();
        assertThat(row.getTxHash()).as("重放不覆盖").isEqualTo("tx-first");
        assertThat(row.getSettledAmount()).isEqualByComparingTo("100");
        assertThat(row.getSettledCurrency()).isEqualTo("USDT");
    }

    @Autowired
    private TonPayReferenceRepository repository;

    private TonPayReferenceRepository repository() {
        return repository;
    }
}
