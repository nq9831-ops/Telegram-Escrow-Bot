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
import com.tg.escrow.core.AnomalyDetector.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 设备关联检测（ET-64：同一设备/IP 关联多账号 → 标可疑）的行为固定测试。
 *
 * <p>与 {@code AnomalyDetector} 同族：<b>只标记不处罚</b>（人工复核）。判定定死为
 * <b>严格超过</b>账号阈值（关联数 > threshold 才可疑，恰好等于不算）。
 */
class DeviceLinkAnalyzerTest {

    @Test
    @DisplayName("同一设备关联多账号（超过阈值）→ SUSPICIOUS")
    void multiAccountDeviceSuspicious() {
        Map<String, Set<Long>> devices = Map.of("dev-1", Set.of(1L, 2L, 3L, 4L));

        assertThat(DeviceLinkAnalyzer.assess(devices, 3)).isEqualTo(Verdict.SUSPICIOUS);
    }

    @Test
    @DisplayName("恰好等于阈值不可疑（严格超过才算）")
    void exactlyAtThresholdNormal() {
        Map<String, Set<Long>> devices = Map.of("dev-1", Set.of(1L, 2L, 3L));

        assertThat(DeviceLinkAnalyzer.assess(devices, 3)).isEqualTo(Verdict.NORMAL);
    }

    @Test
    @DisplayName("设备各自单账号 → NORMAL")
    void singleAccountDevicesNormal() {
        Map<String, Set<Long>> devices = Map.of("a", Set.of(1L), "b", Set.of(2L));

        assertThat(DeviceLinkAnalyzer.assess(devices, 3)).isEqualTo(Verdict.NORMAL);
    }

    @Test
    @DisplayName("空数据 → NORMAL（无从判断不臆断）；null 映射/阈值 < 1 → fail-closed")
    void invalidInputs() {
        assertThat(DeviceLinkAnalyzer.assess(Map.of(), 3)).isEqualTo(Verdict.NORMAL);
        assertThatThrownBy(() -> DeviceLinkAnalyzer.assess(null, 3)).isInstanceOf(TggException.class);
        assertThatThrownBy(() -> DeviceLinkAnalyzer.assess(Map.of(), 0)).isInstanceOf(TggException.class);
    }
}
