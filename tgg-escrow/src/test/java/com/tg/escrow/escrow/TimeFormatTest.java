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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 时刻展示形态（TimeFormat）的行为固定测试。
 *
 * <p>钉住三件事：人可读（无 {@code T}/{@code Z} 噪声）、显式 UTC 标注（杜绝本地/UTC 误读）、
 * 分钟粒度（秒对"何时过期"无信息价值）、null 直通。
 */
class TimeFormatTest {

    @Test
    @DisplayName("ISO 裸串 → 人可读短 UTC（无 T/Z 噪声、带 UTC 标注）")
    void formatsHumanReadableUtc() {
        assertThat(TimeFormat.shortUtc(Instant.parse("2026-10-04T05:33:00Z")))
                .isEqualTo("2026-10-04 05:33 UTC");
    }

    @Test
    @DisplayName("秒级截断到分钟——05:33:59 也显示 05:33")
    void secondPrecisionTruncatedToMinute() {
        assertThat(TimeFormat.shortUtc(Instant.parse("2026-10-04T05:33:59Z")))
                .isEqualTo("2026-10-04 05:33 UTC");
    }

    @Test
    @DisplayName("非 UTC 偏移的 Instant 归一为 UTC 展示（Instant 本身是 UTC 基线）")
    void alwaysRendersInUtc() {
        assertThat(TimeFormat.shortUtc(Instant.parse("2026-10-03T23:00:00Z")))
                .isEqualTo("2026-10-03 23:00 UTC");
    }

    @Test
    @DisplayName("null 原样返回 null——缺省文案由调用方决定，本类不替它编")
    void nullPassesThrough() {
        assertThat(TimeFormat.shortUtc(null)).isNull();
    }
}
