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

import com.tg.escrow.escrow.VoterRegistry.Tier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 投票资格分层（ET-41，业务冲突 1.3 的解法）的行为固定测试。
 *
 * <p>分层解决"新用户永远拿不到投票资格"的枯竭问题：<b>观察员（≥1 笔）可讨论但无投票权</b>，
 * 满 5 笔起有投票权且权重随完成数递增。边界必须逐个钉死——"≥20"含不含 20 会直接改变权重。
 */
class VoterRegistryTest {

    @Test
    @DisplayName("≥20 笔 → 核心投票人，权重 3")
    void coreTier() {
        assertThat(VoterRegistry.tierOf(20)).hasValue(Tier.CORE);
        assertThat(VoterRegistry.weightOf(Tier.CORE)).isEqualTo(3);
        assertThat(VoterRegistry.tierOf(200)).hasValue(Tier.CORE);
    }

    @Test
    @DisplayName("≥10 笔 → 资深投票人，权重 2")
    void seniorTier() {
        assertThat(VoterRegistry.tierOf(10)).hasValue(Tier.SENIOR);
        assertThat(VoterRegistry.weightOf(Tier.SENIOR)).isEqualTo(2);
        assertThat(VoterRegistry.tierOf(19)).hasValue(Tier.SENIOR);
    }

    @Test
    @DisplayName("≥5 笔 → 普通投票人，权重 1")
    void regularTier() {
        assertThat(VoterRegistry.tierOf(5)).hasValue(Tier.REGULAR);
        assertThat(VoterRegistry.weightOf(Tier.REGULAR)).isEqualTo(1);
        assertThat(VoterRegistry.tierOf(9)).hasValue(Tier.REGULAR);
    }

    @Test
    @DisplayName("≥1 笔 → 观察员：可参与但无投票权（防新用户被排除在外）")
    void observerTier() {
        assertThat(VoterRegistry.tierOf(1)).hasValue(Tier.OBSERVER);
        assertThat(VoterRegistry.tierOf(4)).hasValue(Tier.OBSERVER);
        assertThat(VoterRegistry.canVote(Tier.OBSERVER)).isFalse();
    }

    @Test
    @DisplayName("0 笔 → 无资格（不是观察员）")
    void noTradeNoTier() {
        assertThat(VoterRegistry.tierOf(0)).isEmpty();
    }

    @Test
    @DisplayName("有投票权的三层 canVote 均为 true")
    void votingTiersCanVote() {
        assertThat(VoterRegistry.canVote(Tier.CORE)).isTrue();
        assertThat(VoterRegistry.canVote(Tier.SENIOR)).isTrue();
        assertThat(VoterRegistry.canVote(Tier.REGULAR)).isTrue();
    }

    @Test
    @DisplayName("负完成数 → 抛（数据异常不按 0 笔静默降级）")
    void negativeCountFailsClosed() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> VoterRegistry.tierOf(-1))
                .isInstanceOf(com.tg.escrow.common.EscrowException.class);
    }
}
