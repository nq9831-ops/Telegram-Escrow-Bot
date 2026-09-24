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
 * 刷屏检测（G7）的行为固定测试。
 *
 * <p>这类"判定"代码最怕两件事：把正常人误判成刷屏，以及阈值边界随实现漂移。
 * 所以本测试把归一化规则、窗口衰减、阈值语义（<b>达到即算</b>）逐条钉死。
 */
class MessageRepeatDetectorTest {

    private static final Duration WINDOW = Duration.ofSeconds(10);
    private static final Instant T0 = Instant.parse("2026-09-23T00:00:00Z");

    private static MessageRepeatDetector detector(int threshold) {
        return new MessageRepeatDetector(WINDOW, threshold);
    }

    @Test
    @DisplayName("窗口内同一内容达到阈值即判刷屏（达到即算，非超过）")
    void reachingThresholdFlagsSpam() {
        MessageRepeatDetector d = detector(3);

        assertThat(d.isSpam("hello", T0)).isFalse(); // 1
        assertThat(d.isSpam("hello", T0)).isFalse(); // 2
        assertThat(d.isSpam("hello", T0)).isTrue();  // 3 == 阈值
    }

    @Test
    @DisplayName("不同内容各自计数，互不累加")
    void differentContentCountsSeparately() {
        MessageRepeatDetector d = detector(3);

        d.isSpam("a", T0);
        d.isSpam("b", T0);
        d.isSpam("c", T0);

        assertThat(d.isSpam("a", T0)).isFalse(); // a 的第 2 次，未达阈值
    }

    @Test
    @DisplayName("大小写与首尾空白差异视为同一内容（归一化后比对）")
    void normalizationTreatsCaseAndWhitespaceAsSame() {
        MessageRepeatDetector d = detector(3);

        d.isSpam("Hello", T0);
        d.isSpam("hello", T0);

        assertThat(d.isSpam("  HELLO  ", T0)).isTrue();
    }

    @Test
    @DisplayName("窗口滑出后计数衰减——超出窗口的旧消息不再计入")
    void countsDecayAfterWindowSlides() {
        MessageRepeatDetector d = detector(3);

        d.isSpam("x", T0);
        d.isSpam("x", T0.plusSeconds(20)); // 此刻 T0 已滑出 10s 窗口

        assertThat(d.isSpam("x", T0.plusSeconds(20))).isFalse(); // 窗口内第 2 次
        assertThat(d.isSpam("x", T0.plusSeconds(20))).isTrue();  // 窗口内第 3 次
    }

    @Test
    @DisplayName("空消息 / null / 纯空白不计入——非文本消息是常态，不该累加刷屏")
    void blankOrNullNotCounted() {
        MessageRepeatDetector d = detector(2);

        assertThat(d.isSpam("", T0)).isFalse();
        assertThat(d.isSpam(null, T0)).isFalse();
        assertThat(d.isSpam("   ", T0)).isFalse();
    }

    @Test
    @DisplayName("阈值 < 2 在构造期拒绝——阈值 1 意味着每条消息都是刷屏")
    void thresholdBelowTwoRejected() {
        assertThatThrownBy(() -> detector(1)).isInstanceOf(TggException.class);
        assertThatThrownBy(() -> detector(0)).isInstanceOf(TggException.class);
    }

    @Test
    @DisplayName("窗口为空 / 负 / null 在构造期拒绝（零窗口会让计数永远为空，行为不可解释）")
    void nonPositiveWindowRejected() {
        assertThatThrownBy(() -> new MessageRepeatDetector(Duration.ZERO, 3))
                .isInstanceOf(TggException.class);
        assertThatThrownBy(() -> new MessageRepeatDetector(Duration.ofSeconds(-1), 3))
                .isInstanceOf(TggException.class);
        assertThatThrownBy(() -> new MessageRepeatDetector(null, 3))
                .isInstanceOf(TggException.class);
    }

    @Test
    @DisplayName("互异内容不得令内部状态无界增长——过期内容必须被回收（常驻进程的内存防线）")
    void distinctContentDoesNotGrowUnbounded() {
        MessageRepeatDetector d = new MessageRepeatDetector(Duration.ofSeconds(10), 3);

        Instant t = T0;
        for (int i = 0; i < 1000; i++) {
            d.isSpam("msg-" + i, t);
            t = t.plusSeconds(1); // 每秒一条互异内容，旧内容 10s 后全部滑出窗口
        }

        // 窗口 10s，任何时刻活跃内容只有个位数；若 key 从不回收则会是 1000
        assertThat(d.trackedContentCount()).isLessThan(100);
    }

    @Test
    @DisplayName("清扫不得误删窗口内的活跃内容（回收只针对已滑出的 key）")
    void sweepKeepsActiveContent() {
        MessageRepeatDetector d = new MessageRepeatDetector(Duration.ofSeconds(60), 3);

        d.isSpam("spam", T0);
        Instant t = T0.plusSeconds(1);
        for (int i = 0; i < 200; i++) {
            d.isSpam("other-" + i, t);
            t = t.plusMillis(100);
        }

        // "spam" 仍在 60s 窗口内：第 2 次不触发，第 3 次触发
        assertThat(d.isSpam("spam", T0.plusSeconds(30))).isFalse();
        assertThat(d.isSpam("spam", T0.plusSeconds(30))).isTrue();
    }
}
