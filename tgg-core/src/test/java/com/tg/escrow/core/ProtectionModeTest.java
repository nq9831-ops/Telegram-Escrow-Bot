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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 保护模式（G27）的行为固定测试。
 *
 * <p>语义：开启时拒绝新入群申请（通常由滑动窗口检测触发）。开启必须<b>带原因</b>——
 * 无原因的"保护模式"会让管理员只看到"申请被拒"却不知为何，无法判断是误触发还是攻击。
 */
class ProtectionModeTest {

    @Test
    @DisplayName("初始为关闭")
    void startsOff() {
        ProtectionMode p = new ProtectionMode();

        assertThat(p.isEnabled()).isFalse();
        assertThat(p.reason()).isNull();
        assertThat(p.shouldRejectJoin()).isFalse();
    }

    @Test
    @DisplayName("enable 后开启，并保留原因")
    void enableKeepsReason() {
        ProtectionMode p = new ProtectionMode();

        p.enable("1 分钟内 12 次入群申请");
        assertThat(p.isEnabled()).isTrue();
        assertThat(p.reason()).isEqualTo("1 分钟内 12 次入群申请");
        assertThat(p.shouldRejectJoin()).isTrue();
    }

    @Test
    @DisplayName("disable 后关闭，原因清空")
    void disableClearsReason() {
        ProtectionMode p = new ProtectionMode();
        p.enable("攻击");

        p.disable();
        assertThat(p.isEnabled()).isFalse();
        assertThat(p.reason()).isNull();
        assertThat(p.shouldRejectJoin()).isFalse();
    }

    @Test
    @DisplayName("重复 enable 更新原因（幂等）")
    void reEnableUpdatesReason() {
        ProtectionMode p = new ProtectionMode();
        p.enable("原因一");

        p.enable("原因二");
        assertThat(p.reason()).isEqualTo("原因二");
    }

    @Test
    @DisplayName("空原因 → 拒绝（无原因的保护模式无法排查）")
    void blankReasonRejected() {
        ProtectionMode p = new ProtectionMode();

        assertThatThrownBy(() -> p.enable("")).isInstanceOf(TggException.class);
        assertThatThrownBy(() -> p.enable("   ")).isInstanceOf(TggException.class);
        assertThatThrownBy(() -> p.enable(null)).isInstanceOf(TggException.class);
        assertThat(p.isEnabled()).isFalse();
    }
}
