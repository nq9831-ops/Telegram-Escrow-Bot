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
 * 重放保护（ET-48）的行为固定测试。
 *
 * <p>核心语义：每签名请求绑定唯一 nonce，<b>有效期内重复即拒</b>。两条不变量：
 * ①重复拒绝（安全核心）；②<b>nonce 按窗口回收</b>（内存防线——不回收即随请求量无界增长，
 * MessageRepeatDetector 的教训在此固化为回归）。
 */
class ReplayGuardTest {

    private static final Duration WINDOW = Duration.ofMinutes(10);
    private static final Instant T0 = Instant.parse("2026-09-23T00:00:00Z");

    private static ReplayGuard guard() {
        return new ReplayGuard(WINDOW);
    }

    @Test
    @DisplayName("首次 nonce → 接受；有效期内重复 → 拒绝（重放攻击拦截）")
    void duplicateRejected() {
        ReplayGuard g = guard();

        assertThat(g.accept("n1", T0)).isTrue();
        assertThat(g.accept("n1", T0.plusSeconds(5))).isFalse();
    }

    @Test
    @DisplayName("不同 nonce 各自独立")
    void distinctNoncesIndependent() {
        ReplayGuard g = guard();

        assertThat(g.accept("a", T0)).isTrue();
        assertThat(g.accept("b", T0)).isTrue();
    }

    @Test
    @DisplayName("窗口外 nonce 被回收：同一值可再次接受，且内部状态不随请求量无界增长")
    void windowEviction() {
        ReplayGuard g = guard();

        assertThat(g.accept("n1", T0)).isTrue();
        assertThat(g.accept("n1", T0.plus(WINDOW).plusSeconds(1))).isTrue(); // 旧记录已过期回收

        // 内存防线：灌 1000 个互异 nonce（每秒 1 个），驻留≈窗口容量 600，远小于总量 1000
        Instant t = T0.plusSeconds(200);
        for (int i = 0; i < 1000; i++) {
            g.accept("m" + i, t);
            t = t.plusSeconds(1);
        }
        assertThat(g.trackedCount()).isLessThan(700);
    }

    @Test
    @DisplayName("空白 nonce / 空窗口 → fail-closed")
    void invalidRejected() {
        assertThatThrownBy(() -> guard().accept("", T0)).isInstanceOf(TggException.class);
        assertThatThrownBy(() -> guard().accept(null, T0)).isInstanceOf(TggException.class);
        assertThatThrownBy(() -> new ReplayGuard(Duration.ZERO)).isInstanceOf(TggException.class);
    }
}
