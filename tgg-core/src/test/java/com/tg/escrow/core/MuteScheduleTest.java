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
 * 定时禁言（GM-02 的到期判定侧）的行为固定测试。
 *
 * <p>到期边界定死为<b>闭区间</b>（{@code now >= 起始+时长} 即到期自动解除），与项目内
 * 其它时限语义一致。时长必须为正——零时长禁言是语义噪音。
 */
class MuteScheduleTest {

    private static final Instant T0 = Instant.parse("2026-09-23T00:00:00Z");

    private static MuteSchedule mute(String minutes) {
        return new MuteSchedule(20L, T0, Duration.ofMinutes(Long.parseLong(minutes)));
    }

    @Test
    @DisplayName("禁言期内 → 未到期")
    void activeWithinPeriod() {
        assertThat(mute("10").isExpired(T0.plus(Duration.ofMinutes(9)))).isFalse();
    }

    @Test
    @DisplayName("恰好到期即解除（闭区间：now >= 起始+时长）")
    void expiresExactlyAtDeadline() {
        MuteSchedule m = mute("10");

        assertThat(m.isExpired(T0.plus(Duration.ofMinutes(10)))).isTrue();
        assertThat(m.isExpired(T0.plus(Duration.ofMinutes(10)).minusNanos(1))).isFalse();
    }

    @Test
    @DisplayName("绑定用户 ID")
    void bindsUser() {
        assertThat(mute("10").userId()).isEqualTo(20L);
    }

    @Test
    @DisplayName("零/负时长、空起始 → 构造期拒绝")
    void invalidRejected() {
        assertThatThrownBy(() -> new MuteSchedule(20L, T0, Duration.ZERO))
                .isInstanceOf(TggException.class);
        assertThatThrownBy(() -> new MuteSchedule(20L, null, Duration.ofMinutes(1)))
                .isInstanceOf(TggException.class);
    }
}
