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
package com.tg.escrow.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 入群爆发自动保护（把 {@link ProtectionMode} 文档里"通常由滑动窗口检测触发"从承诺变成实现）。
 *
 * <p>测的重点是<b>边界与解除</b>：恰好等于上限不该触发（沿用 {@link SlidingWindowCounter}
 * 的"严格超过"语义，不另立一套）；触发后必须能<b>自动解除</b>——否则一次误触发会让群
 * 永久拒绝新成员，而项目没有调度器。
 */
class JoinBurstGuardTest {

    private static final Instant T0 = Instant.parse("2026-09-25T12:00:00Z");
    private static final Duration WINDOW = Duration.ofMinutes(1);
    private static final Duration COOLDOWN = Duration.ofMinutes(10);

    /** 可推进的时钟：跨时间的行为（冷却期解除）必须能测。 */
    private static final class TickingClock extends Clock {
        private Instant now;

        TickingClock(Instant start) {
            this.now = start;
        }

        void advance(Duration amount) {
            this.now = now.plus(amount);
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

    private static JoinBurstGuard guard(ProtectionMode mode, int maxJoins, Clock clock) {
        return new JoinBurstGuard(WINDOW, maxJoins, COOLDOWN, mode, clock);
    }

    @Test
    @DisplayName("窗口内入群数超过上限 → 自动开启保护模式（并给出可读原因）")
    void burstEnablesProtection() {
        ProtectionMode mode = new ProtectionMode();
        JoinBurstGuard guard = guard(mode, 3, new TickingClock(T0));

        for (int i = 0; i < 4; i++) {   // 4 > 3
            guard.onJoin();
        }

        assertThat(mode.shouldRejectJoin()).isTrue();
        assertThat(mode.reason())
                .as("管理员要知道为何被拦，否则无法判断是误触发还是真实攻击")
                .isNotBlank();
    }

    @Test
    @DisplayName("恰好等于上限 → 不触发（严格超过才算爆发，与 SlidingWindowCounter 语义一致）")
    void atThresholdDoesNotTrigger() {
        ProtectionMode mode = new ProtectionMode();
        JoinBurstGuard guard = guard(mode, 3, new TickingClock(T0));

        for (int i = 0; i < 3; i++) {
            guard.onJoin();
        }

        assertThat(mode.shouldRejectJoin()).isFalse();
    }

    @Test
    @DisplayName("冷却期内即使安静也不解除（否则攻击者只要慢下来就能立刻恢复）")
    void staysProtectedDuringCooldown() {
        ProtectionMode mode = new ProtectionMode();
        TickingClock clock = new TickingClock(T0);
        JoinBurstGuard guard = guard(mode, 3, clock);
        for (int i = 0; i < 4; i++) {
            guard.onJoin();
        }

        clock.advance(COOLDOWN.minusSeconds(1));
        guard.onJoin();   // 冷却未满时的一次入群

        assertThat(mode.shouldRejectJoin()).as("冷却未满不得自动解除").isTrue();
    }

    @Test
    @DisplayName("冷却期满后自动解除（惰性判定，无需调度器——本项目没有调度器，也不打算有）")
    void autoReleasesAfterCooldown() {
        ProtectionMode mode = new ProtectionMode();
        TickingClock clock = new TickingClock(T0);
        JoinBurstGuard guard = guard(mode, 3, clock);
        for (int i = 0; i < 4; i++) {
            guard.onJoin();
        }

        clock.advance(COOLDOWN.plusSeconds(1));
        guard.onJoin();

        assertThat(mode.shouldRejectJoin())
                .as("一次误触发不能导致群永久拒人")
                .isFalse();
    }

    @Test
    @DisplayName("解除后再次爆发 → 重新开启（不是一次性开关）")
    void reTriggersAfterRelease() {
        ProtectionMode mode = new ProtectionMode();
        TickingClock clock = new TickingClock(T0);
        JoinBurstGuard guard = guard(mode, 3, clock);
        for (int i = 0; i < 4; i++) {
            guard.onJoin();
        }
        clock.advance(COOLDOWN.plusSeconds(1));
        guard.onJoin();   // 解除
        assertThat(mode.shouldRejectJoin()).isFalse();

        for (int i = 0; i < 4; i++) {
            guard.onJoin();
        }

        assertThat(mode.shouldRejectJoin()).as("再次爆发应再次开启").isTrue();
    }

    @Test
    @DisplayName("未配置上限（≤0）→ 完全不检测、绝不改动保护模式（默认不改行为）")
    void disabledWhenMaxJoinsNotConfigured() {
        ProtectionMode mode = new ProtectionMode();
        JoinBurstGuard guard = guard(mode, 0, new TickingClock(T0));

        for (int i = 0; i < 100; i++) {
            guard.onJoin();
        }

        assertThat(mode.shouldRejectJoin())
                .as("默认配置下不得擅自开启保护模式——那会把所有人挡在门外")
                .isFalse();
    }

    @Test
    @DisplayName("构造参数非法 → 抛（fail-fast，不静默降级成「不检测」）")
    void rejectsInvalidArguments() {
        ProtectionMode mode = new ProtectionMode();

        assertThatThrownBy(() -> new JoinBurstGuard(null, 3, COOLDOWN, mode,
                Clock.fixed(T0, ZoneOffset.UTC)))
                .isInstanceOf(com.tg.escrow.common.TggException.class);
        assertThatThrownBy(() -> new JoinBurstGuard(WINDOW, 3, null, mode,
                Clock.fixed(T0, ZoneOffset.UTC)))
                .isInstanceOf(com.tg.escrow.common.TggException.class);
        assertThatThrownBy(() -> new JoinBurstGuard(WINDOW, 3, COOLDOWN, null,
                Clock.fixed(T0, ZoneOffset.UTC)))
                .isInstanceOf(com.tg.escrow.common.TggException.class);
        assertThatThrownBy(() -> new JoinBurstGuard(WINDOW, 3, COOLDOWN, mode, null))
                .isInstanceOf(com.tg.escrow.common.TggException.class);
    }
}
