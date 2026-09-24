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
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 维护期服务的行为固定测试（Wave 4 接线）。
 *
 * <p>钉住三件事：① 只有 {@code DELIVERED} 才有维护期；② 窗口内报剩余、窗口外报过期；
 * ③ 过期判定与超时策略同源（两处各算一套会漂移）。
 */
class TradeMaintenanceServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-24T10:00:00Z");
    private static final Clock FIXED = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final long BUYER = 1001L;
    private static final long SELLER = 2002L;
    private static final Duration WINDOW = Duration.ofHours(24);

    private static TradeMaintenanceService service(Clock clock) {
        MaintenanceWindow window = new MaintenanceWindow(
                List.of(Duration.ofHours(1), Duration.ofHours(6), WINDOW,
                        Duration.ofHours(72), Duration.ofHours(168)), 2);
        TradeTimeoutPolicy policy = new TradeTimeoutPolicy(Map.of(
                EscrowOrder.State.DELIVERED,
                new TradeTimeoutPolicy.TimeoutRule(WINDOW, TradeTimeoutPolicy.TimeoutAction.AUTO_CONFIRM)));
        return new TradeMaintenanceService(window, policy, clock);
    }

    /** 在 {@code deliveredAt} 交付的订单。 */
    private static EscrowOrder deliveredAt(Instant deliveredAt) {
        EscrowOrder order = new EscrowOrder(BUYER, SELLER, new BigDecimal("100"), "USDT", deliveredAt);
        order.markLocked(deliveredAt);
        order.markDelivered(deliveredAt);
        return order;
    }

    @Test
    @DisplayName("未交付（LOCKED）→ 维护期不适用")
    void notApplicableBeforeDelivery() {
        EscrowOrder order = new EscrowOrder(BUYER, SELLER, new BigDecimal("100"), "USDT", NOW);
        order.markLocked(NOW);

        assertThat(service(FIXED).statusOf(order).applicable()).isFalse();
    }

    @Test
    @DisplayName("交付后窗口内 → 报剩余时长")
    void reportsRemainingWithinWindow() {
        EscrowOrder order = deliveredAt(NOW.minus(Duration.ofHours(6)));

        TradeMaintenanceService.MaintenanceStatus status = service(FIXED).statusOf(order);

        assertThat(status.applicable()).isTrue();
        assertThat(status.expired()).isFalse();
        assertThat(status.remaining()).isEqualTo(Duration.ofHours(18));
    }

    @Test
    @DisplayName("超过窗口 → 报已过期（判定与超时策略同源）")
    void reportsExpiredAfterWindow() {
        EscrowOrder order = deliveredAt(NOW.minus(Duration.ofHours(25)));

        TradeMaintenanceService.MaintenanceStatus status = service(FIXED).statusOf(order);

        assertThat(status.applicable()).isTrue();
        assertThat(status.expired()).isTrue();
        assertThat(status.remaining()).isZero();
    }

    @Test
    @DisplayName("选项数量对外可见（供界面呈现）、默认时长取配置项")
    void exposesOptionCountAndDefault() {
        assertThat(service(FIXED).optionCount()).isEqualTo(MaintenanceWindow.OPTION_COUNT);
    }

    @Test
    @DisplayName("构造：依赖缺失或订单为 null → 抛")
    void failsClosed() {
        assertThatThrownBy(() -> new TradeMaintenanceService(null, null, FIXED))
                .isInstanceOf(EscrowException.class);
        assertThatThrownBy(() -> service(FIXED).statusOf(null))
                .isInstanceOf(EscrowException.class);
    }
}
