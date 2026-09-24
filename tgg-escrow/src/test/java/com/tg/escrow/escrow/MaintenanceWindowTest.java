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

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 维护期窗口（T26）的行为固定测试。
 *
 * <p>维护期是放款后"还能申请售后"的时间窗。要求：<b>恰好 5 个时长选项</b>、
 * <b>默认项可配置且合法</b>，以及到期判定为<b>闭区间</b>（{@code now >= deadline} 到期）。
 */
class MaintenanceWindowTest {

    private static final Instant T0 = Instant.parse("2026-09-23T00:00:00Z");

    private static MaintenanceWindow window() {
        return new MaintenanceWindow(List.of(
                Duration.ofHours(1),
                Duration.ofHours(24),
                Duration.ofDays(3),
                Duration.ofDays(7),
                Duration.ofDays(30)), 2); // 默认第 3 项 = 3 天
    }

    @Test
    @DisplayName("恰好 5 个选项；默认项按索引返回")
    void fiveOptionsWithConfigurableDefault() {
        MaintenanceWindow w = window();

        assertThat(w.optionCount()).isEqualTo(5);
        assertThat(w.defaultDuration()).isEqualTo(Duration.ofDays(3));
    }

    @Test
    @DisplayName("按 1–5 选择时长；越界（0 / 6）拒绝")
    void choiceRangeIsOneToFive() {
        MaintenanceWindow w = window();

        assertThat(w.durationAt(1)).isEqualTo(Duration.ofHours(1));
        assertThat(w.durationAt(5)).isEqualTo(Duration.ofDays(30));
        assertThatThrownBy(() -> w.durationAt(0)).isInstanceOf(EscrowException.class);
        assertThatThrownBy(() -> w.durationAt(6)).isInstanceOf(EscrowException.class);
    }

    @Test
    @DisplayName("选项数量不是 5 → 构造期拒绝")
    void wrongOptionCountRejected() {
        assertThatThrownBy(() -> new MaintenanceWindow(List.of(Duration.ofDays(1)), 0))
                .isInstanceOf(EscrowException.class);
        assertThatThrownBy(() -> new MaintenanceWindow(null, 0))
                .isInstanceOf(EscrowException.class);
    }

    @Test
    @DisplayName("默认项索引越界 → 构造期拒绝")
    void defaultIndexOutOfRangeRejected() {
        assertThatThrownBy(() -> new MaintenanceWindow(
                List.of(Duration.ofHours(1), Duration.ofHours(2), Duration.ofHours(3),
                        Duration.ofHours(4), Duration.ofHours(5)), 5))
                .isInstanceOf(EscrowException.class);
    }

    @Test
    @DisplayName("到期判定：未到为 false，恰好到达即 true（闭区间）")
    void expiryIsClosedInterval() {
        MaintenanceWindow w = window();
        Duration span = Duration.ofDays(3);

        assertThat(w.isExpired(T0, span, T0.plus(Duration.ofDays(2)))).isFalse();
        assertThat(w.isExpired(T0, span, T0.plus(Duration.ofDays(3)))).isTrue();
        assertThat(w.isExpired(T0, span, T0.plus(Duration.ofDays(4)))).isTrue();
    }
}
