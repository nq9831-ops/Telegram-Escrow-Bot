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
import com.tg.escrow.escrow.VoterRegistry.Tier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 可疑关系降级（ET-68：标记后限制投票资格）的行为固定测试。
 *
 * <p>被标记可疑（互刷/设备关联等）→ 投票资格<b>降为观察员</b>（可讨论、无投票权）——
 * 处罚方向与 {@code PenaltyPlanner.VOTE_SUSPEND} 一致但更轻：撤销票权、不撤销参与。
 */
class SuspiciousRelationPolicyTest {

    @Test
    @DisplayName("未标记 → 等级原样")
    void cleanKeepsTier() {
        assertThat(SuspiciousRelationPolicy.effective(Tier.CORE, false)).isEqualTo(Tier.CORE);
    }

    @Test
    @DisplayName("标记可疑 → 降为观察员（无论原等级）")
    void flaggedDemotedToObserver() {
        assertThat(SuspiciousRelationPolicy.effective(Tier.CORE, true)).isEqualTo(Tier.OBSERVER);
        assertThat(SuspiciousRelationPolicy.effective(Tier.SENIOR, true)).isEqualTo(Tier.OBSERVER);
    }

    @Test
    @DisplayName("null 等级 → fail-closed")
    void nullTierRejected() {
        assertThatThrownBy(() -> SuspiciousRelationPolicy.effective(null, false))
                .isInstanceOf(EscrowException.class);
    }
}
