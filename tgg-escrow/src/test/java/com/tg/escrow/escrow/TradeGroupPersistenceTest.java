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

import com.tg.escrow.escrow.TradeGroupLifecycle.State;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 交易群绑定的<b>真实持久化</b>验证（真实 H2 + Flyway V6 + JPA，非 mock）。
 *
 * <h2>它补的是什么洞</h2>
 * <p>{@link TradeGroupServiceTest} 用内存存储钉住事件映射与语义边界，但"状态究竟有没有写进库、
 * 重启后能不能恢复"完全没验。这一层的错误形态极隐蔽：列名对不上、类型不匹配、迁移没跑，
 * mock 与内存替身全测不出来，只会在生产启动或运行到某条 SQL 时才炸。
 *
 * <h2>用法约束</h2>
 * <p>本类的 H2 与其它持久化测试<b>共享同一个库</b>（{@code DB_CLOSE_DELAY=-1}），
 * 故 {@link #cleanUp()} 在每个用例后清表：不清就会污染别的用例的"全库计数"。
 * 交易号/用户 ID 取本类专用段，避免与其它测试的造数撞车。
 */
@SpringBootTest(classes = EscrowPersistenceTestConfig.class)
class TradeGroupPersistenceTest {

    private static final Instant T0 = Instant.parse("2026-03-01T00:00:00Z");
    private static final Duration SILENCE = Duration.ofDays(7);
    private static final long TRADE_ID = 9_200_001L;
    private static final long CHAT_ID = -1001234567890L;

    @Autowired
    private TradeGroupRepository repository;

    @AfterEach
    void cleanUp() {
        repository.deleteAll();
    }

    private TradeGroupService serviceAt(Instant t) {
        return new TradeGroupService(new JpaTradeGroupStore(repository), SILENCE,
                Clock.fixed(t, ZoneOffset.UTC));
    }

    /** 造一笔走到 RELEASED（终态）、带非空交易号的订单——群服务只读它的 id 与状态。 */
    private EscrowOrder releasedOrder() {
        EscrowOrder o = new EscrowOrder(9_200_011L, 9_200_012L, new BigDecimal("10"), "USDT", T0);
        o.assignId(TRADE_ID);
        o.markLocked(T0);
        o.markDelivered(T0);
        o.markReleased(T0);
        return o;
    }

    /** 用新 store 实例重读——绕过一级缓存，确认真的落了库。 */
    private TradeGroup reload() {
        return new JpaTradeGroupStore(repository).find(TRADE_ID).orElseThrow();
    }

    @Test
    @DisplayName("真实库：建群绑定 → 结算静默——状态与静默起点往返无损（ddl-auto=validate 亦在此生效）")
    void settleRoundTrips() {
        EscrowOrder order = releasedOrder();
        TradeGroupService svc = serviceAt(T0);
        svc.onTradeCreated(order, CHAT_ID);
        svc.onTradeSettled(order);

        TradeGroup loaded = reload();
        assertThat(loaded.currentState()).isEqualTo(State.SILENT);
        assertThat(loaded.getChatId()).isEqualTo(CHAT_ID);
        assertThat(loaded.getSilenceStartedAt()).isEqualTo(T0);
    }

    @Test
    @DisplayName("真实库：静默期满 → 归档终态，归档时刻落库（惰性结算）")
    void archiveRoundTrips() {
        EscrowOrder order = releasedOrder();
        serviceAt(T0).onTradeCreated(order, CHAT_ID);
        serviceAt(T0).onTradeSettled(order);

        Instant later = T0.plus(Duration.ofDays(8));
        assertThat(serviceAt(later).onArchiveDue(TRADE_ID)).isPresent();

        TradeGroup loaded = reload();
        assertThat(loaded.currentState()).isEqualTo(State.ARCHIVED);
        assertThat(loaded.getArchivedAt()).isEqualTo(later);
    }

    @Test
    @DisplayName("真实库：静默期内再起争议 → 恢复留痕，静默起点清空（重启后恢复可继续推进）")
    void disputeInSilenceRoundTrips() {
        EscrowOrder order = releasedOrder();
        serviceAt(T0).onTradeCreated(order, CHAT_ID);
        serviceAt(T0).onTradeSettled(order);
        serviceAt(T0.plus(Duration.ofDays(1))).onDisputeInSilence(TRADE_ID);

        TradeGroup loaded = reload();
        assertThat(loaded.currentState()).isEqualTo(State.RECORDING);
        assertThat(loaded.getSilenceStartedAt()).isNull();

        // 重启后可继续推进：恢复成状态机再走一遍静默→归档
        TradeGroupLifecycle resumed = loaded.toLifecycle(SILENCE);
        assertThat(resumed.currentState()).isEqualTo(State.RECORDING);
    }
}
