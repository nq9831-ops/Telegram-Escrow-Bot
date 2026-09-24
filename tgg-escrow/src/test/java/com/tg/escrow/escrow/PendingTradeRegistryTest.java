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
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 待确认交易登记（ET-34 前置校验）的行为固定测试。
 *
 * <p>闭环语义：{@code create} 登记请求 → {@code confirm} 校验「同买方、参数一致、未过期」→
 * 一次性消费。未经 {@code create} 直接 {@code confirm} 一律拒绝（风险提示不可被绕过）。
 */
class PendingTradeRegistryTest {

    private static final Instant T0 = Instant.parse("2026-09-23T00:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(10);

    /** 可推进时钟：钉死 TTL 边界。 */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static TradeInitiationRequest req(long seller, String amount) {
        return new TradeInitiationRequest(1001L, seller, new BigDecimal(amount), "USDT");
    }

    @Test
    @DisplayName("create 登记后，confirm 一致参数 → 消费成功")
    void prepareThenConsumeMatching() {
        PendingTradeRegistry r = new PendingTradeRegistry(TTL, Clock.fixed(T0, ZoneOffset.UTC));
        r.prepare(req(2002L, "100"));

        assertThat(r.consume(req(2002L, "100"))).isPresent();
    }

    @Test
    @DisplayName("未经 create 直接 confirm → 拒绝（防绕过风险提示）")
    void consumeWithoutPrepareRejected() {
        PendingTradeRegistry r = new PendingTradeRegistry(TTL, Clock.fixed(T0, ZoneOffset.UTC));

        assertThat(r.consume(req(2002L, "100"))).isEmpty();
    }

    @Test
    @DisplayName("金额被改（参数不一致）→ 拒绝")
    void consumeMismatchedAmountRejected() {
        PendingTradeRegistry r = new PendingTradeRegistry(TTL, Clock.fixed(T0, ZoneOffset.UTC));
        r.prepare(req(2002L, "100"));

        assertThat(r.consume(req(2002L, "999"))).isEmpty();
    }

    @Test
    @DisplayName("卖方被换（参数不一致）→ 拒绝")
    void consumeMismatchedSellerRejected() {
        PendingTradeRegistry r = new PendingTradeRegistry(TTL, Clock.fixed(T0, ZoneOffset.UTC));
        r.prepare(req(2002L, "100"));

        assertThat(r.consume(req(3003L, "100"))).isEmpty();
    }

    @Test
    @DisplayName("一次性：消费成功后再次 confirm → 拒绝")
    void consumeIsOneShot() {
        PendingTradeRegistry r = new PendingTradeRegistry(TTL, Clock.fixed(T0, ZoneOffset.UTC));
        r.prepare(req(2002L, "100"));

        assertThat(r.consume(req(2002L, "100"))).isPresent();
        assertThat(r.consume(req(2002L, "100"))).isEmpty();
    }

    @Test
    @DisplayName("恰好到 TTL 边界 → 仍有效；过一秒 → 失效（需重新预览）")
    void ttlBoundary() {
        MutableClock clock = new MutableClock(T0);
        PendingTradeRegistry r = new PendingTradeRegistry(TTL, clock);
        r.prepare(req(2002L, "100"));

        clock.advance(TTL);                        // 恰好 TTL：仍有效
        assertThat(r.consume(req(2002L, "100"))).isPresent();

        r.prepare(req(2002L, "100"));
        clock.advance(TTL.plusSeconds(1));         // 超过 TTL：失效
        assertThat(r.consume(req(2002L, "100"))).isEmpty();
    }

    @Test
    @DisplayName("重新 create 覆盖旧登记（同一买方只认最近一次）")
    void prepareOverwrites() {
        PendingTradeRegistry r = new PendingTradeRegistry(TTL, Clock.fixed(T0, ZoneOffset.UTC));
        r.prepare(req(2002L, "100"));
        r.prepare(req(3003L, "500"));

        assertThat(r.consume(req(2002L, "100"))).isEmpty();   // 旧参数已失效
        assertThat(r.consume(req(3003L, "500"))).isPresent();
    }

    @Test
    @DisplayName("不同买方互不干扰")
    void isolatedPerBuyer() {
        PendingTradeRegistry r = new PendingTradeRegistry(TTL, Clock.fixed(T0, ZoneOffset.UTC));
        r.prepare(new TradeInitiationRequest(1001L, 2002L, new BigDecimal("100"), "USDT"));

        assertThat(r.consume(
                new TradeInitiationRequest(1002L, 2002L, new BigDecimal("100"), "USDT")))
                .isEmpty();
    }

    @Test
    @DisplayName("构造：TTL 非正或时钟缺失 → 抛")
    void ctorRejectsInvalid() {
        assertThatThrownBy(() -> new PendingTradeRegistry(Duration.ZERO, Clock.systemUTC()))
                .isInstanceOf(EscrowException.class);
        assertThatThrownBy(() -> new PendingTradeRegistry(null, Clock.systemUTC()))
                .isInstanceOf(EscrowException.class);
        assertThatThrownBy(() -> new PendingTradeRegistry(TTL, null))
                .isInstanceOf(EscrowException.class);
    }

    @Test
    @DisplayName("prepare/consume 空请求 → 抛")
    void nullsRejected() {
        PendingTradeRegistry r = new PendingTradeRegistry(TTL, Clock.fixed(T0, ZoneOffset.UTC));

        assertThatThrownBy(() -> r.prepare(null)).isInstanceOf(EscrowException.class);
        assertThatThrownBy(() -> r.consume(null)).isInstanceOf(EscrowException.class);
    }
}
