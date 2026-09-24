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

import com.tg.escrow.core.FraudLinkage.Action;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 欺诈联动（ET-04：欺诈触发群封禁）的行为固定测试。
 *
 * <p>判定结果（互刷/设备关联/行为突变）→ 处置建议。定死：多信号叠加升档
 * （≥2 个信号 → 建议封禁；1 个 → 观察名单；0 个 → 不动作）。<b>只建议不执行</b>——
 * 执行走 {@code ModerationOrchestrator}（单调守卫），本类是情报侧。
 */
class FraudLinkageTest {

    @Test
    @DisplayName("零信号 → 不动作")
    void cleanIsNoAction() {
        assertThat(FraudLinkage.recommend(false, false, false)).isEqualTo(Action.NONE);
    }

    @Test
    @DisplayName("单信号 → 观察名单（单点证据不足以封禁）")
    void singleSignalWatchlist() {
        assertThat(FraudLinkage.recommend(true, false, false)).isEqualTo(Action.WATCHLIST);
        assertThat(FraudLinkage.recommend(false, true, false)).isEqualTo(Action.WATCHLIST);
    }

    @Test
    @DisplayName("≥2 信号叠加 → 建议封禁")
    void multiSignalBanRecommended() {
        assertThat(FraudLinkage.recommend(true, true, false)).isEqualTo(Action.BAN_RECOMMENDED);
        assertThat(FraudLinkage.recommend(true, true, true)).isEqualTo(Action.BAN_RECOMMENDED);
    }
}
