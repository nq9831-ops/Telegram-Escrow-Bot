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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 危险动作二次确认槽（release / refund）的行为固定测试。
 *
 * <p>闭环语义：第 1 次输入 {@code offer} 登记槽位 → 第 2 次输入 {@code consume} 校验
 * 「同一订单、同一用户、同一动作、未过期」→ 一次性消费。未经 offer 直接确认一律拒绝
 * （二次确认不可被绕过）。
 */
class ConfirmGateTest {

    private static final Instant T0 = Instant.parse("2026-10-03T00:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(5);

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

    private static ConfirmGate gate(Clock clock) {
        return new ConfirmGate(TTL, clock);
    }

    @Test
    @DisplayName("offer 后 consume 同键同动作 → 命中")
    void offerThenConsumeHits() {
        ConfirmGate g = gate(Clock.fixed(T0, ZoneOffset.UTC));
        g.offer(7L, 100L, "rl");

        assertThat(g.consume(7L, 100L, "rl")).isTrue();
    }

    @Test
    @DisplayName("未经 offer 直接 consume → 拒绝（二次确认不可绕过）")
    void consumeWithoutOfferRejected() {
        ConfirmGate g = gate(Clock.fixed(T0, ZoneOffset.UTC));

        assertThat(g.consume(7L, 100L, "rl")).isFalse();
    }

    @Test
    @DisplayName("动作不符 → 拒绝，且原槽位保留（换回正确动作仍可确认）")
    void actionMismatchKeepsSlot() {
        ConfirmGate g = gate(Clock.fixed(T0, ZoneOffset.UTC));
        g.offer(7L, 100L, "rl");

        assertThat(g.consume(7L, 100L, "rf")).isFalse();   // 发起的是放款，却说退款
        assertThat(g.consume(7L, 100L, "rl")).isTrue();    // 正确动作仍可消费
    }

    @Test
    @DisplayName("一次性：消费成功后再次 consume → 拒绝")
    void consumeIsOneShot() {
        ConfirmGate g = gate(Clock.fixed(T0, ZoneOffset.UTC));
        g.offer(7L, 100L, "rl");

        assertThat(g.consume(7L, 100L, "rl")).isTrue();
        assertThat(g.consume(7L, 100L, "rl")).isFalse();
    }

    @Test
    @DisplayName("恰好到 TTL 边界 → 仍有效；过一秒 → 失效（需重新发起）")
    void ttlBoundary() {
        MutableClock clock = new MutableClock(T0);
        ConfirmGate g = gate(clock);
        g.offer(7L, 100L, "rl");

        clock.advance(TTL);                          // 恰好 TTL：仍有效
        assertThat(g.consume(7L, 100L, "rl")).isTrue();

        g.offer(7L, 100L, "rl");
        clock.advance(TTL.plusSeconds(1));           // 超过 TTL：失效
        assertThat(g.consume(7L, 100L, "rl")).isFalse();
    }

    @Test
    @DisplayName("键 =（订单号, 用户 ID）：同一订单下两人各持独立槽位（互不覆盖、互不干扰）")
    void isolatedPerUserPerOrder() {
        ConfirmGate g = gate(Clock.fixed(T0, ZoneOffset.UTC));
        g.offer(7L, 100L, "rl");                     // 买方发起
        g.offer(7L, 200L, "rf");                     // 卖方发起（争议态双方同权）

        assertThat(g.consume(7L, 100L, "rl")).isTrue();   // 买方确认自己的动作
        assertThat(g.consume(7L, 200L, "rf")).isTrue();   // 卖方确认自己的动作
        assertThat(g.consume(7L, 100L, "rf")).isFalse();  // 对方不可替你确认
    }

    @Test
    @DisplayName("不同订单互不干扰")
    void isolatedPerOrder() {
        ConfirmGate g = gate(Clock.fixed(T0, ZoneOffset.UTC));
        g.offer(7L, 100L, "rl");

        assertThat(g.consume(8L, 100L, "rl")).isFalse();
        assertThat(g.consume(7L, 100L, "rl")).isTrue();
    }

    @Test
    @DisplayName("重复 offer 覆盖旧动作（同键只认最近一次）")
    void offerOverwrites() {
        ConfirmGate g = gate(Clock.fixed(T0, ZoneOffset.UTC));
        g.offer(7L, 100L, "rl");
        g.offer(7L, 100L, "rf");

        assertThat(g.consume(7L, 100L, "rl")).isFalse();   // 旧动作已失效
        assertThat(g.consume(7L, 100L, "rf")).isTrue();
    }

    @Test
    @DisplayName("内存上界：过期槽位随新 offer 被清扫（size 不随未确认动作单调增长）")
    void expiredSlotsArePurgedOnOffer() {
        MutableClock clock = new MutableClock(T0);
        ConfirmGate g = gate(clock);
        g.offer(1L, 100L, "rl");
        g.offer(2L, 100L, "rl");
        assertThat(g.size()).isEqualTo(2);

        clock.advance(TTL.plusSeconds(1));           // 两个槽位过期
        g.offer(3L, 100L, "rl");                     // 新 offer 顺带清扫过期的两个

        assertThat(g.size()).isEqualTo(1);           // 只剩新槽位，未确认动作不驻留
    }

    @Test
    @DisplayName("构造：TTL 非正或时钟缺失 → 抛")
    void ctorRejectsInvalid() {
        assertThatThrownBy(() -> new ConfirmGate(Duration.ZERO, Clock.systemUTC()))
                .isInstanceOf(EscrowException.class);
        assertThatThrownBy(() -> new ConfirmGate(Duration.ofMinutes(-1), Clock.systemUTC()))
                .isInstanceOf(EscrowException.class);
        assertThatThrownBy(() -> new ConfirmGate(null, Clock.systemUTC()))
                .isInstanceOf(EscrowException.class);
        assertThatThrownBy(() -> new ConfirmGate(TTL, null))
                .isInstanceOf(EscrowException.class);
    }

    @Test
    @DisplayName("offer 非法参数 → 抛（订单号/用户 ID 须为正，动作不可空）")
    void offerRejectsBadInput() {
        ConfirmGate g = gate(Clock.fixed(T0, ZoneOffset.UTC));

        assertThatThrownBy(() -> g.offer(0L, 100L, "rl")).isInstanceOf(EscrowException.class);
        assertThatThrownBy(() -> g.offer(7L, 0L, "rl")).isInstanceOf(EscrowException.class);
        assertThatThrownBy(() -> g.offer(7L, 100L, "  ")).isInstanceOf(EscrowException.class);
        assertThatThrownBy(() -> g.offer(7L, 100L, null)).isInstanceOf(EscrowException.class);
    }

    @Test
    @DisplayName("并发确认：同一条槽位只能被消费一次（check-then-act 竞态的回归防护）")
    void concurrentConsumeIsOneShot() throws Exception {
        ConfirmGate g = gate(Clock.fixed(T0, ZoneOffset.UTC));
        g.offer(7L, 100L, "rl");

        int threads = 8;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                barrier.await();                                 // 尽量让所有线程同时冲进 consume
                return g.consume(7L, 100L, "rl");
            }));
        }
        int consumed = 0;
        for (Future<Boolean> f : results) {
            if (f.get()) {
                consumed++;
            }
        }
        pool.shutdown();

        // 若 consume 是 get → 判定 → remove 三步，窗口内多个线程可同时判定通过（consumed > 1）。
        // 现在整个判定与移除在 ConcurrentHashMap.compute 里对同一 key 串行，恒为 1。
        assertThat(consumed).isEqualTo(1);
    }
}
