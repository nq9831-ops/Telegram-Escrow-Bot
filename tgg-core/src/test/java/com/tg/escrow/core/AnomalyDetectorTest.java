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

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 账号行为突变检测（GM-27 / ET-73）的行为固定测试。
 *
 * <p>语义：近期活跃相对基线<b>突增</b>超过倍数因子 → 标可疑。<b>只标记不处罚</b>——
 * 输出交人工复核（行为突变可能是正常换设备，来源 08 潜在冲突）。保守原则：
 * <b>无基线（0）不臆断</b>——数据缺失是采集问题，不是用户问题。
 */
class AnomalyDetectorTest {

    private static final BigDecimal FACTOR = new BigDecimal("3");

    @Test
    @DisplayName("近期活跃超过基线 3 倍 → SUSPICIOUS（仅标记）")
    void spikeIsSuspicious() {
        assertThat(AnomalyDetector.assess(10, 2, FACTOR)).isEqualTo(Verdict.SUSPICIOUS);
    }

    @Test
    @DisplayName("恰好 3 倍不可疑——\"突增超过\"不含本数（边界定死）")
    void exactlyAtFactorNotSuspicious() {
        assertThat(AnomalyDetector.assess(6, 2, FACTOR)).isEqualTo(Verdict.NORMAL);
    }

    @Test
    @DisplayName("无基线（0 笔）→ NORMAL（不臆断新行为为突变）")
    void noBaselineIsNormal() {
        assertThat(AnomalyDetector.assess(100, 0, FACTOR)).isEqualTo(Verdict.NORMAL);
    }

    @Test
    @DisplayName("活跃下降 → NORMAL")
    void declineIsNormal() {
        assertThat(AnomalyDetector.assess(1, 50, FACTOR)).isEqualTo(Verdict.NORMAL);
    }

    @Test
    @DisplayName("负计数 / 因子 < 1 → fail-closed")
    void invalidInputsRejected() {
        assertThatThrownBy(() -> AnomalyDetector.assess(-1, 2, FACTOR))
                .isInstanceOf(TggException.class);
        assertThatThrownBy(() -> AnomalyDetector.assess(2, 2, BigDecimal.ONE.subtract(BigDecimal.ONE)))
                .isInstanceOf(TggException.class);
    }
}
