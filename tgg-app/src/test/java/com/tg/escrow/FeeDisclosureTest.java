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
package com.tg.escrow;

import com.tg.escrow.escrow.EscrowOrder;
import com.tg.escrow.escrow.FeeLedgerPort;
import com.tg.escrow.escrow.FeePolicy;
import com.tg.escrow.escrow.TraderStatsPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 放款费用台账（ET-29/30）的行为固定测试——钉住
 * {@link FeeDisclosure#recordIfReleased} 的四件事：
 * <b>值的口径</b>（金额/费率/实收/豁免标记）、<b>只记放款</b>、<b>没台账不炸</b>、
 * <b>台账失败不抛</b>（旁路语义——不阻断已完成的交易）。
 */
class FeeDisclosureTest {

    private static final long BUYER = 1001L;
    private static final long SELLER = 2002L;
    private static final Instant T0 = Instant.parse("2026-10-03T00:00:00Z");

    /** 记录型台账：把「记没记、记了什么」变成可断言的物理事实。 */
    private static final class RecordingLedger implements FeeLedgerPort {
        final List<Entry> entries = new ArrayList<>();
        boolean failNext;

        @Override
        public void record(Entry entry) {
            if (failNext) {
                failNext = false;
                throw new RuntimeException("库不可用");
            }
            entries.add(entry);
        }
    }

    /** 统计替身：记账路径不消费它（recordIfReleased 只接 prior 整数参数——值来自调用方快照）。 */
    private static TraderStatsPort unusedStats() {
        return StubTraderStats.EMPTY;
    }

    private static EscrowOrder order() {
        EscrowOrder order = new EscrowOrder(BUYER, SELLER, new BigDecimal("100"), "USDT", T0);
        order.assignId(7L);
        return order;
    }

    @Test
    @DisplayName("放款记账：金额/平台费/实收/豁免标记四元组落账（费率 1%、非首单）")
    void recordsReleasedLedger() {
        RecordingLedger ledger = new RecordingLedger();
        FeeDisclosure disclosure = new FeeDisclosure(
                FeePolicy.of("0.01", "0", "0", true), unusedStats(), ledger);

        disclosure.recordIfReleased(order(), TradeEvent.RELEASED, 3);

        assertThat(ledger.entries).singleElement().satisfies(e -> {
            assertThat(e.orderId()).isEqualTo(7L);
            assertThat(e.currency()).isEqualTo("USDT");
            assertThat(e.grossAmount()).isEqualByComparingTo("100");
            assertThat(e.platformFee()).as("100 × 1%").isEqualByComparingTo("1");
            assertThat(e.sellerNet()).isEqualByComparingTo("99");
            assertThat(e.firstOrderWaived()).isFalse();
        });
    }

    @Test
    @DisplayName("首单豁免：前期 0 笔 → 平台费 0、卖方全额、waived 标记 true")
    void recordsFirstOrderWaiver() {
        RecordingLedger ledger = new RecordingLedger();
        FeeDisclosure disclosure = new FeeDisclosure(
                FeePolicy.of("0.01", "0", "0", true), unusedStats(), ledger);

        disclosure.recordIfReleased(order(), TradeEvent.RELEASED, 0);

        assertThat(ledger.entries).singleElement().satisfies(e -> {
            assertThat(e.platformFee()).isEqualByComparingTo("0");
            assertThat(e.sellerNet()).isEqualByComparingTo("100");
            assertThat(e.firstOrderWaived()).isTrue();
        });
    }

    @Test
    @DisplayName("费率本为 0（默认不收费）：平台费 0 但不算豁免（waived=false）——对账要能分开看")
    void zeroRateIsNotWaiver() {
        RecordingLedger ledger = new RecordingLedger();
        FeeDisclosure disclosure = new FeeDisclosure(FeePolicy.zero(), unusedStats(), ledger);

        disclosure.recordIfReleased(order(), TradeEvent.RELEASED, 0);

        assertThat(ledger.entries).singleElement().satisfies(e -> {
            assertThat(e.platformFee()).isEqualByComparingTo("0");
            assertThat(e.firstOrderWaived()).isFalse();
        });
    }

    @Test
    @DisplayName("非放款事件：零记账（台账只定格放款账目）")
    void nonReleasedEventsAreNotRecorded() {
        RecordingLedger ledger = new RecordingLedger();
        FeeDisclosure disclosure = new FeeDisclosure(
                FeePolicy.of("0.01", "0", "0", true), unusedStats(), ledger);

        disclosure.recordIfReleased(order(), TradeEvent.LOCKED, 0);
        disclosure.recordIfReleased(order(), TradeEvent.DELIVERED, 0);
        disclosure.recordIfReleased(order(), TradeEvent.DISPUTED, 0);

        assertThat(ledger.entries).isEmpty();
    }

    @Test
    @DisplayName("未装配台账（null）：不炸（存量构造路径语义不变）")
    void nullLedgerIsNoop() {
        FeeDisclosure disclosure = new FeeDisclosure(
                FeePolicy.of("0.01", "0", "0", true), unusedStats());

        assertThatCode(() -> disclosure.recordIfReleased(order(), TradeEvent.RELEASED, 0))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("台账写入失败：不抛（旁路——账目缺口不改动已完成的交易）")
    void ledgerFailureDoesNotThrow() {
        RecordingLedger ledger = new RecordingLedger();
        ledger.failNext = true;
        FeeDisclosure disclosure = new FeeDisclosure(
                FeePolicy.of("0.01", "0", "0", true), unusedStats(), ledger);

        assertThatCode(() -> disclosure.recordIfReleased(order(), TradeEvent.RELEASED, 0))
                .doesNotThrowAnyException();
        assertThat(ledger.entries).isEmpty();
    }
}
