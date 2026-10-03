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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 费用台账持久化（V12）：往返保真 + 幂等。
 *
 * <p>本类同时是 V12 迁移在 H2 MODE=MySQL 下的兼容性证明（flyway 真跑 + ddl-auto=validate
 * ——实体与 DDL 对不上会在启动期失败）。
 */
@SpringBootTest(classes = EscrowPersistenceTestConfig.class)
class FeeLedgerPersistenceTest {

    /** 本类专用订单段（远离其它测试）。 */
    private static final long ORDER_A = 9_300_001L;
    private static final long ORDER_B = 9_300_002L;

    @Autowired
    private FeeLedgerPort store;

    @Autowired
    private FeeLedgerRepository repository;

    private static FeeLedgerPort.Entry entry(long orderId) {
        return new FeeLedgerPort.Entry(orderId, "USDT", new BigDecimal("100"),
                new BigDecimal("1"), new BigDecimal("99"), false);
    }

    @Test
    @DisplayName("往返：账目落库可查、金额保真、recorded_at 由 DB 生成（非空）")
    void roundTrip() {
        store.record(entry(ORDER_A));

        List<FeeLedgerRow> rows = repository.findTop100ByOrderByIdDesc();
        FeeLedgerRow row = rows.stream().filter(r -> r.getOrderId() == ORDER_A)
                .findFirst().orElseThrow();

        assertThat(row.getCurrency()).isEqualTo("USDT");
        assertThat(row.getGrossAmount()).isEqualByComparingTo("100");
        assertThat(row.getPlatformFee()).isEqualByComparingTo("1");
        assertThat(row.getSellerNet()).isEqualByComparingTo("99");
        assertThat(row.isFirstOrderWaived()).isFalse();
        assertThat(row.getRecordedAt()).as("DB 默认时间戳").isNotNull();
    }

    @Test
    @DisplayName("幂等：同订单重复记账 → 仍然只有一行（重放/重试不重复入账）")
    void idempotentOnOrder() {
        store.record(entry(ORDER_B));
        store.record(entry(ORDER_B));
        store.record(entry(ORDER_B));

        assertThat(repository.findTop100ByOrderByIdDesc().stream()
                .filter(r -> r.getOrderId() == ORDER_B)).hasSize(1);
    }
}
