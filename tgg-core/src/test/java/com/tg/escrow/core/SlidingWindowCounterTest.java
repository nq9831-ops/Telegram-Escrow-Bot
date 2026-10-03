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

import com.tg.escrow.common.TggException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 滑动窗口计数器（G26）的行为固定测试。
 *
 * <p>阈值语义在此定死为「<b>严格超过</b>才触发」（{@code count > threshold}）——
 * 与 {@link MessageRepeatDetector} 的「达到即算」不同。两处语义各自明确，
 * 测试分别钉住，避免"边界含不含"随实现漂移。
 *
 * <p>窗口区间定死为<b>闭区间</b> {@code [now-window, now]}：恰好落在 {@code now-window}
 * 的记录仍在窗口内。窗口边界是"滑出/保留"最容易出错的地方，必须可复现。
 */
class SlidingWindowCounterTest {

    private static final Duration WINDOW = Duration.ofSeconds(10);
    private static final Instant T0 = Instant.parse("2026-09-23T00:00:00Z");

    @Test
    @DisplayName("严格超过阈值才触发——恰好等于阈值不触发")
    void exceedsNotEquals() {
        SlidingWindowCounter c = new SlidingWindowCounter(WINDOW, 3);

        assertThat(c.recordAndCheck("k", T0)).isFalse(); // 1
        assertThat(c.recordAndCheck("k", T0)).isFalse(); // 2
        assertThat(c.recordAndCheck("k", T0)).isFalse(); // 3 == 阈值，不触发
        assertThat(c.recordAndCheck("k", T0)).isTrue();  // 4 > 阈值，触发
    }

    @Test
    @DisplayName("窗口滑出后计数衰减——超出窗口的旧记录被移除")
    void decaysAfterWindow() {
        SlidingWindowCounter c = new SlidingWindowCounter(WINDOW, 3);
        c.recordAndCheck("k", T0);

        // T0 早于 T0+11-10 = T0+1，已滑出
        assertThat(c.currentCount("k", T0.plusSeconds(11))).isZero();
    }

    @Test
    @DisplayName("窗口为左闭区间：恰好 now-window 的记录仍在窗口内")
    void windowIsClosedOnLeft() {
        SlidingWindowCounter c = new SlidingWindowCounter(WINDOW, 3);
        c.recordAndCheck("k", T0);

        assertThat(c.currentCount("k", T0.plusSeconds(10))).isEqualTo(1);
    }

    @Test
    @DisplayName("不同 key 独立计数")
    void keysAreIndependent() {
        SlidingWindowCounter c = new SlidingWindowCounter(WINDOW, 3);
        c.recordAndCheck("a", T0);

        assertThat(c.currentCount("b", T0)).isZero();
    }

    @Test
    @DisplayName("阈值 < 1 或窗口非正在构造期拒绝")
    void invalidConfigRejected() {
        assertThatThrownBy(() -> new SlidingWindowCounter(WINDOW, 0)).isInstanceOf(TggException.class);
        assertThatThrownBy(() -> new SlidingWindowCounter(Duration.ZERO, 3)).isInstanceOf(TggException.class);
        assertThatThrownBy(() -> new SlidingWindowCounter(null, 3)).isInstanceOf(TggException.class);
    }
}
