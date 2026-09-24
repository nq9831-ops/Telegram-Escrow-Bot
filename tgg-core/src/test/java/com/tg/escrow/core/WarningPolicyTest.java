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
import com.tg.escrow.core.WarningPolicy.Action;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 警告超阈值处罚（GM-03 处罚判定侧）的行为固定测试。
 *
 * <p>累计警告到阈值自动触发处罚（13/V3 材料「超阈值自动触发处罚，通知用户」）——
 * 阈值语义定死为<b>达到即触发</b>（≥threshold）。处罚分级：轻（禁言）/重（踢出）按累计严重度。
 */
class WarningPolicyTest {

    @Test
    @DisplayName("未达阈值 → NONE（继续观察）")
    void belowThresholdNoAction() {
        WarningPolicy p = new WarningPolicy(3, 5);

        assertThat(p.actionFor(2)).isEqualTo(Action.NONE);
    }

    @Test
    @DisplayName("达到阈值 → 禁言（达到即触发，含本数）")
    void reachingMuteThreshold() {
        WarningPolicy p = new WarningPolicy(3, 5);

        assertThat(p.actionFor(3)).isEqualTo(Action.MUTE);
    }

    @Test
    @DisplayName("达到重罚阈值 → 踢出")
    void reachingKickThreshold() {
        WarningPolicy p = new WarningPolicy(3, 5);

        assertThat(p.actionFor(5)).isEqualTo(Action.KICK);
    }

    @Test
    @DisplayName("阈值倒置/非正 → 构造期拒绝")
    void invalidThresholdsRejected() {
        assertThatThrownBy(() -> new WarningPolicy(5, 3)).isInstanceOf(TggException.class);
        assertThatThrownBy(() -> new WarningPolicy(0, 3)).isInstanceOf(TggException.class);
    }
}
