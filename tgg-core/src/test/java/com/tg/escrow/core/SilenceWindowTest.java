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

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 静默时段（G19）的行为固定测试。
 *
 * <p>最容易错的是 <b>跨午夜</b>：{@code 22:00–02:00} 不是"22 点到 2 点之间的时钟数"
 * （那样会得到空区间），而是 {22:00–24:00} ∪ {00:00–02:00}。半夜是群管理告警最集中的时段，
 * 判定错了等于静默期在半夜失效。
 *
 * <p>边界定死：含起点、不含终点。
 */
class SilenceWindowTest {

    private static final ZoneId ZONE = ZoneId.of("UTC");

    private static Instant at(int hour, int minute) {
        return ZonedDateTime.of(2026, 9, 23, hour, minute, 0, 0, ZONE).toInstant();
    }

    @Test
    @DisplayName("普通时段内 → 静默")
    void insideNormalWindow() {
        SilenceWindow w = new SilenceWindow(LocalTime.of(9, 0), LocalTime.of(17, 0), ZONE);

        assertThat(w.isSilenced(at(12, 0))).isTrue();
    }

    @Test
    @DisplayName("普通时段外 → 不静默")
    void outsideNormalWindow() {
        SilenceWindow w = new SilenceWindow(LocalTime.of(9, 0), LocalTime.of(17, 0), ZONE);

        assertThat(w.isSilenced(at(8, 59))).isFalse();
        assertThat(w.isSilenced(at(18, 0))).isFalse();
    }

    @Test
    @DisplayName("普通时段边界：含起点、不含终点")
    void normalWindowBounds() {
        SilenceWindow w = new SilenceWindow(LocalTime.of(9, 0), LocalTime.of(17, 0), ZONE);

        assertThat(w.isSilenced(at(9, 0))).isTrue();
        assertThat(w.isSilenced(at(17, 0))).isFalse();
    }

    @Test
    @DisplayName("跨午夜时段：午夜前（23:00）在区间内")
    void crossMidnightBeforeMidnight() {
        SilenceWindow w = new SilenceWindow(LocalTime.of(22, 0), LocalTime.of(2, 0), ZONE);

        assertThat(w.isSilenced(at(23, 0))).isTrue();
    }

    @Test
    @DisplayName("跨午夜时段：午夜后（01:00）仍在区间内")
    void crossMidnightAfterMidnight() {
        SilenceWindow w = new SilenceWindow(LocalTime.of(22, 0), LocalTime.of(2, 0), ZONE);

        assertThat(w.isSilenced(at(1, 0))).isTrue();
    }

    @Test
    @DisplayName("跨午夜时段：白天（12:00）不在区间内")
    void crossMidnightDaytimeNotSilenced() {
        SilenceWindow w = new SilenceWindow(LocalTime.of(22, 0), LocalTime.of(2, 0), ZONE);

        assertThat(w.isSilenced(at(12, 0))).isFalse();
    }

    @Test
    @DisplayName("跨午夜边界：含起点 22:00、不含终点 02:00")
    void crossMidnightBounds() {
        SilenceWindow w = new SilenceWindow(LocalTime.of(22, 0), LocalTime.of(2, 0), ZONE);

        assertThat(w.isSilenced(at(22, 0))).isTrue();
        assertThat(w.isSilenced(at(2, 0))).isFalse();
        assertThat(w.isSilenced(at(21, 59))).isFalse();
    }

    @Test
    @DisplayName("start == end → 构造期拒绝（语义歧义：全天还是零长度）")
    void equalBoundsRejected() {
        assertThatThrownBy(() -> new SilenceWindow(LocalTime.of(22, 0), LocalTime.of(22, 0), ZONE))
                .isInstanceOf(TggException.class);
    }

    @Test
    @DisplayName("null 参数 → 构造期拒绝")
    void nullArgsRejected() {
        assertThatThrownBy(() -> new SilenceWindow(null, LocalTime.of(2, 0), ZONE))
                .isInstanceOf(TggException.class);
        assertThatThrownBy(() -> new SilenceWindow(LocalTime.of(22, 0), null, ZONE))
                .isInstanceOf(TggException.class);
        assertThatThrownBy(() -> new SilenceWindow(LocalTime.of(22, 0), LocalTime.of(2, 0), null))
                .isInstanceOf(TggException.class);
    }
}
