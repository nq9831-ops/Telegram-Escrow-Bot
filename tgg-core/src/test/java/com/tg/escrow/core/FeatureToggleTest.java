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
 * 功能开关（G14）的行为固定测试。
 *
 * <p>核心是 <b>fail-closed</b>：未登记的功能默认<b>关闭</b>。这样"新功能忘了登记"
 * 会立刻表现为"开关没生效"（可排查），而不是"默认全群开启"（静默的权限漏洞）。
 */
class FeatureToggleTest {

    @Test
    @DisplayName("未登记的功能 → 关闭（fail-closed）")
    void unregisteredIsOff() {
        FeatureToggle t = new FeatureToggle();

        assertThat(t.isEnabled(1L, "escrow")).isFalse();
    }

    @Test
    @DisplayName("enable 后开启")
    void enableTurnsOn() {
        FeatureToggle t = new FeatureToggle();

        t.enable(1L, "escrow");
        assertThat(t.isEnabled(1L, "escrow")).isTrue();
    }

    @Test
    @DisplayName("disable 后回到关闭")
    void disableTurnsOff() {
        FeatureToggle t = new FeatureToggle();
        t.enable(1L, "escrow");

        t.disable(1L, "escrow");
        assertThat(t.isEnabled(1L, "escrow")).isFalse();
    }

    @Test
    @DisplayName("按群组隔离：A 群开启不影响 B 群（B 仍 fail-closed）")
    void isolatedByGroup() {
        FeatureToggle t = new FeatureToggle();
        t.enable(1L, "escrow");

        assertThat(t.isEnabled(1L, "escrow")).isTrue();
        assertThat(t.isEnabled(2L, "escrow")).isFalse();
    }

    @Test
    @DisplayName("按功能隔离：同一群内不同功能互不影响")
    void isolatedByFeature() {
        FeatureToggle t = new FeatureToggle();
        t.enable(1L, "escrow");

        assertThat(t.isEnabled(1L, "escrow")).isTrue();
        assertThat(t.isEnabled(1L, "welcome")).isFalse();
    }

    @Test
    @DisplayName("空白功能名 → 拒绝（空 key 会让所有群共享同一条开关）")
    void blankFeatureRejected() {
        FeatureToggle t = new FeatureToggle();

        assertThatThrownBy(() -> t.enable(1L, "")).isInstanceOf(TggException.class);
        assertThatThrownBy(() -> t.isEnabled(1L, null)).isInstanceOf(TggException.class);
    }
}
