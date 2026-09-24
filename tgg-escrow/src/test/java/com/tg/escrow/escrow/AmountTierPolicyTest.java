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
import com.tg.escrow.escrow.AmountTierPolicy.Tier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 交易金额分层验证（ET-33）的行为固定测试。
 *
 * <p>三档语义来自核心规则「≤100 正常；100-1000 二次确认；≥1000 额外验证」——
 * 原文的区间写法在 100/1000 处重叠，此处<b>定死归属</b>：100 归 NORMAL、1000 归 EXTRA
 * （高风险侧从严）。阈值可配（默认 100/1000 由调用方注入）。
 */
class AmountTierPolicyTest {

    private static final AmountTierPolicy POLICY =
            new AmountTierPolicy(new BigDecimal("100"), new BigDecimal("1000"));

    @Test
    @DisplayName("≤100 → 正常（含 100 本身）")
    void normalUpTo100() {
        assertThat(POLICY.tierOf(new BigDecimal("0.01"))).isEqualTo(Tier.NORMAL);
        assertThat(POLICY.tierOf(new BigDecimal("100"))).isEqualTo(Tier.NORMAL);
    }

    @Test
    @DisplayName("(100, 1000) → 二次确认（含小数贴近边界）")
    void confirmBetween() {
        assertThat(POLICY.tierOf(new BigDecimal("100.01"))).isEqualTo(Tier.CONFIRM);
        assertThat(POLICY.tierOf(new BigDecimal("999.99"))).isEqualTo(Tier.CONFIRM);
    }

    @Test
    @DisplayName("≥1000 → 额外验证（含 1000 本身，高风险侧从严）")
    void extraFrom1000() {
        assertThat(POLICY.tierOf(new BigDecimal("1000"))).isEqualTo(Tier.EXTRA);
        assertThat(POLICY.tierOf(new BigDecimal("100000"))).isEqualTo(Tier.EXTRA);
    }

    @Test
    @DisplayName("阈值可配：改阈值即改分档")
    void thresholdsConfigurable() {
        AmountTierPolicy p = new AmountTierPolicy(new BigDecimal("50"), new BigDecimal("200"));

        assertThat(p.tierOf(new BigDecimal("50"))).isEqualTo(Tier.NORMAL);
        assertThat(p.tierOf(new BigDecimal("200"))).isEqualTo(Tier.EXTRA);
    }

    @Test
    @DisplayName("金额非正 / null、阈值非正 / 倒置 → fail-closed")
    void invalidInputsRejected() {
        assertThatThrownBy(() -> POLICY.tierOf(BigDecimal.ZERO)).isInstanceOf(EscrowException.class);
        assertThatThrownBy(() -> POLICY.tierOf(null)).isInstanceOf(EscrowException.class);
        assertThatThrownBy(() -> new AmountTierPolicy(BigDecimal.ZERO, new BigDecimal("1000")))
                .isInstanceOf(EscrowException.class);
        assertThatThrownBy(() -> new AmountTierPolicy(new BigDecimal("1000"), new BigDecimal("100")))
                .isInstanceOf(EscrowException.class);
    }
}
