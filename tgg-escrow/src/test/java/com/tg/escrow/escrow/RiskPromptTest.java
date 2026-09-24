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

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 交易前风险提示（ET-34：创建交易前强制展示）的行为固定测试。
 *
 * <p>按 {@code AmountTierPolicy} 分档给提示强度：正常→轻提示、二次确认→明确确认、
 * 额外验证→强警示。文案必须含金额与风险要点（"说人话"且不泄漏技术术语）。
 */
class RiskPromptTest {

    private static final AmountTierPolicy POLICY =
            new AmountTierPolicy(new BigDecimal("100"), new BigDecimal("1000"));

    @Test
    @DisplayName("正常档 → 轻提示（含金额）")
    void normalTierPrompt() {
        String prompt = RiskPrompt.forAmount(new BigDecimal("50"), POLICY);

        assertThat(prompt).contains("50").contains("确认");
    }

    @Test
    @DisplayName("二次确认档 → 提示强调二次确认")
    void confirmTierPrompt() {
        String prompt = RiskPrompt.forAmount(new BigDecimal("500"), POLICY);

        assertThat(prompt).contains("二次确认");
    }

    @Test
    @DisplayName("额外验证档 → 强警示（含风险要点）")
    void extraTierPrompt() {
        String prompt = RiskPrompt.forAmount(new BigDecimal("5000"), POLICY);

        assertThat(prompt).contains("额外验证").contains("风险");
    }

    @Test
    @DisplayName("null 输入 → fail-closed")
    void nullArgsRejected() {
        assertThatThrownBy(() -> RiskPrompt.forAmount(null, POLICY)).isInstanceOf(EscrowException.class);
        assertThatThrownBy(() -> RiskPrompt.forAmount(BigDecimal.ONE, null)).isInstanceOf(EscrowException.class);
    }
}
