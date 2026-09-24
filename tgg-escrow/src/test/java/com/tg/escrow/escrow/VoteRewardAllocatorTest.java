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
import com.tg.escrow.escrow.VoteRewardAllocator.Allocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 投票奖励分配（ET-44）的行为固定测试。
 *
 * <p><b>总额守恒是不变量</b>：分出给投票者 + 管理费 ≤ 没收收入，违反即抛——
 * 核心规则「没收资金分层：补偿被挑战方→奖励投对者→划入仲裁基金」的数字实现。
 * 任何舍入误差也必须流向仲裁基金，不得凭空多发。
 */
class VoteRewardAllocatorTest {

    private static VoteRewardAllocator.Allocation allocate(String income, String feeRate,
                                                           List<BigDecimal> correctWeights) {
        return new VoteRewardAllocator(new BigDecimal(feeRate)).allocate(new BigDecimal(income), correctWeights);
    }

    @Test
    @DisplayName("正常分配：管理费 + 投对者 + 基金 = 总收入（守恒）")
    void conservationHolds() {
        Allocation a = allocate("10", "0.1", List.of(BigDecimal.ONE, BigDecimal.valueOf(3)));

        assertThat(a.managementFee()).isEqualByComparingTo("1");
        assertThat(a.rewards()).hasSize(2);
        assertThat(a.rewards().get(0)).isEqualByComparingTo("2.25");  // 9 * 1/4
        assertThat(a.rewards().get(1)).isEqualByComparingTo("6.75");  // 9 * 3/4
        assertThat(a.toArbitrationFund()).isEqualByComparingTo("0");  // 恰好分完
    }

    @Test
    @DisplayName("舍入误差流向仲裁基金，不多发（守恒在分位边界仍成立）")
    void roundingSpillsToFund() {
        // 1 元分给 3 个等权投票者：每人 0.33（scale 2），合计 0.99，误差 0.01 必须落基金而非被谁吞掉
        Allocation a = allocate("1", "0", List.of(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE));

        assertThat(a.rewards()).allMatch(r -> r.compareTo(new BigDecimal("0.33")) == 0);
        assertThat(a.toArbitrationFund()).isEqualByComparingTo("0.01");
    }

    @Test
    @DisplayName("无人投对 → 全额进仲裁基金（不凭空发钱）")
    void noCorrectVotersAllToFund() {
        Allocation a = allocate("10", "0.1", List.of());

        assertThat(a.managementFee()).isEqualByComparingTo("1");
        assertThat(a.rewards()).isEmpty();
        assertThat(a.toArbitrationFund()).isEqualByComparingTo("9");
    }

    @Test
    @DisplayName("权重全部为 0 → 无人可分，余额进基金（不除零）")
    void zeroWeightsAvoidDivideByZero() {
        Allocation a = allocate("6", "0", List.of(BigDecimal.ZERO));

        assertThat(a.rewards().get(0)).isEqualByComparingTo("0");
        assertThat(a.toArbitrationFund()).isEqualByComparingTo("6");
    }

    @Test
    @DisplayName("负收入 / 负权重 / 费率越界 [0,1) → fail-closed")
    void invalidInputsRejected() {
        assertThatThrownBy(() -> allocate("-1", "0.1", List.of(BigDecimal.ONE)))
                .isInstanceOf(EscrowException.class);
        assertThatThrownBy(() -> allocate("10", "0.1", List.of(BigDecimal.valueOf(-2))))
                .isInstanceOf(EscrowException.class);
        assertThatThrownBy(() -> allocate("10", "1", List.of(BigDecimal.ONE)))
                .isInstanceOf(EscrowException.class);
    }
}
