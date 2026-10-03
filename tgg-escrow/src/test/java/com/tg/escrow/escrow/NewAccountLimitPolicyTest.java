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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 新账号交易限制（ET-66：新账号短期内大量交易触发风控）的行为固定测试。
 *
 * <p>两条件<b>同时满足</b>才限制（新账号 且 短期爆发）——单条件不误伤：
 * 老账号爆发可能是活动促销，新账号正常节奏是正常新人。
 */
class NewAccountLimitPolicyTest {

    @Test
    @DisplayName("新账号 + 短期爆发 → 限制")
    void newAndBurstRestricted() {
        assertThat(NewAccountLimitPolicy.isRestricted(3, 5, 7, 4)).isTrue();
    }

    @Test
    @DisplayName("老账号爆发 → 不限制（活动促销不误伤）")
    void oldAccountBurstAllowed() {
        assertThat(NewAccountLimitPolicy.isRestricted(30, 5, 7, 4)).isFalse();
    }

    @Test
    @DisplayName("新账号但节奏正常 → 不限制（正常新人不误伤）")
    void newButNormalAllowed() {
        assertThat(NewAccountLimitPolicy.isRestricted(3, 2, 7, 4)).isFalse();
    }

    @Test
    @DisplayName("边界：账号年龄恰为阈值不算新；爆发恰为阈值不算爆发（严格语义定死）")
    void boundariesStrict() {
        assertThat(NewAccountLimitPolicy.isRestricted(7, 5, 7, 4)).isFalse(); // 年龄 7 不 < 7
        assertThat(NewAccountLimitPolicy.isRestricted(3, 4, 7, 4)).isFalse(); // 4 不 > 4
    }

    @Test
    @DisplayName("负数输入 / 阈值非正 → fail-closed")
    void invalidInputs() {
        assertThatThrownBy(() -> NewAccountLimitPolicy.isRestricted(-1, 1, 7, 4))
                .isInstanceOf(EscrowException.class);
        assertThatThrownBy(() -> NewAccountLimitPolicy.isRestricted(1, 1, 0, 4))
                .isInstanceOf(EscrowException.class);
    }
}
