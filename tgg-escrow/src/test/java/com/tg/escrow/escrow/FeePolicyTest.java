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

import com.tg.escrow.common.TggException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 费率策略（ET-29/30 · B1 已拍板）的行为固定测试。
 *
 * <p>钉住 SPEC 6.5「平台费 / 仲裁费分离」的口径：
 * <ul>
 *   <li><b>平台费</b>：放款时按费率从流转金额扣（默认 0 = 不收费）；</li>
 *   <li><b>仲裁费</b>：独立于平台费，裁决时按费率扣、<b>名义由败诉方承担</b>；</li>
 *   <li><b>联邦管理费</b>：从<b>仲裁费</b>中分成（不是从平台费）。</li>
 * </ul>
 */
class FeePolicyTest {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    @Test
    @DisplayName("默认（全 0 费率）→ 平台费/仲裁费/联邦费全为 0，实收=全额——忘记配置=没收费，不是收错费")
    void zeroRatesChargeNothing() {
        FeePolicy p = FeePolicy.zero();

        assertThat(p.platformFee(HUNDRED)).isEqualByComparingTo("0");
        assertThat(p.arbitrationFee(HUNDRED)).isEqualByComparingTo("0");
        assertThat(p.federationFee(HUNDRED)).isEqualByComparingTo("0");
        assertThat(p.sellerNetOnRelease(HUNDRED)).isEqualByComparingTo("100");
        assertThat(p.netAfterArbitration(HUNDRED)).isEqualByComparingTo("100");
    }

    @Test
    @DisplayName("平台费口径：fee = 金额×费率；放款实收 = 金额 − 平台费")
    void platformFeeIsRateTimesAmount() {
        FeePolicy p = FeePolicy.of("0.02", "0", "0");   // 2%

        assertThat(p.platformFee(HUNDRED)).isEqualByComparingTo("2");
        assertThat(p.sellerNetOnRelease(HUNDRED)).isEqualByComparingTo("98");
    }

    @Test
    @DisplayName("仲裁费独立于平台费：联邦管理费从仲裁费分成（不是从平台费）")
    void federationFeeComesFromArbitrationFeeNotPlatformFee() {
        FeePolicy p = FeePolicy.of("0.02", "0.10", "0.30");   // 平台 2%、仲裁 10%、联邦分仲裁费 30%

        assertThat(p.arbitrationFee(HUNDRED)).isEqualByComparingTo("10");
        assertThat(p.federationFee(HUNDRED)).isEqualByComparingTo("3");   // 10×30%，不是 2×30%
        assertThat(p.netAfterArbitration(HUNDRED)).isEqualByComparingTo("90");  // 败诉方承担：流转金额 − 仲裁费
    }

    @Test
    @DisplayName("平台费不影响仲裁费口径（两费分离——对外文案依据）")
    void platformAndArbitrationAreSeparate() {
        FeePolicy p = FeePolicy.of("0.05", "0.10", "0");

        assertThat(p.platformFee(HUNDRED)).isEqualByComparingTo("5");
        assertThat(p.arbitrationFee(HUNDRED)).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("非法费率 → 构造即拒（负数 / ≥100% / 非法字符串）——fail-fast，不留到运行时；空值=未配置=0")
    void invalidRatesFailFast() {
        assertThatThrownBy(() -> FeePolicy.of("-0.01", "0", "0")).isInstanceOf(TggException.class);
        assertThatThrownBy(() -> FeePolicy.of("1", "0", "0")).isInstanceOf(TggException.class);
        assertThatThrownBy(() -> FeePolicy.of("0", "1.5", "0")).isInstanceOf(TggException.class);
        assertThatThrownBy(() -> FeePolicy.of("0", "0", "abc")).isInstanceOf(TggException.class);
        // 空值/未配置 = 0（容器编排 ${X:-} 产出空串是常态，不当配置错误）
        assertThat(FeePolicy.of(null, "  ", "").platformFee(HUNDRED)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("首单豁免（ET-35 · B2）：卖方此前无成功交易 + 费率非 0 → 平台费免、实收=全额")
    void firstOrderWaivesPlatformFee() {
        FeePolicy p = FeePolicy.of("0.02", "0", "0");   // 2%，首单豁免默认开启

        assertThat(p.firstOrderFree()).isTrue();
        assertThat(p.platformFee(HUNDRED, 0)).isEqualByComparingTo("0");
        assertThat(p.sellerNetOnRelease(HUNDRED, 0)).isEqualByComparingTo("100");
        // 首单豁免只影响平台费——仲裁费口径不受影响（三费分离）
        assertThat(p.arbitrationFee(HUNDRED)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("非首单照常收费：此前已有成功交易（历史笔数 ≥ 1）→ 平台费=金额×费率")
    void nonFirstOrderChargesNormally() {
        FeePolicy p = FeePolicy.of("0.02", "0", "0");

        assertThat(p.platformFee(HUNDRED, 1)).isEqualByComparingTo("2");
        assertThat(p.platformFee(HUNDRED, 7)).isEqualByComparingTo("2");
        assertThat(p.sellerNetOnRelease(HUNDRED, 3)).isEqualByComparingTo("98");
    }

    @Test
    @DisplayName("首单豁免可关：策略关闭时首单照常收费（运营方要收首单费时）")
    void firstOrderFreeCanBeDisabled() {
        FeePolicy p = FeePolicy.of("0.02", "0", "0", false);

        assertThat(p.firstOrderFree()).isFalse();
        assertThat(p.platformFee(HUNDRED, 0)).isEqualByComparingTo("2");
        assertThat(p.sellerNetOnRelease(HUNDRED, 0)).isEqualByComparingTo("98");
    }

    @Test
    @DisplayName("首单判定入参为负（历史成功笔数不可能为负）→ fail-fast，不静默当首单免费")
    void negativePriorCompletedFailsFast() {
        FeePolicy p = FeePolicy.of("0.02", "0", "0");

        assertThatThrownBy(() -> p.platformFee(HUNDRED, -1)).isInstanceOf(TggException.class);
        assertThatThrownBy(() -> p.sellerNetOnRelease(HUNDRED, -1)).isInstanceOf(TggException.class);
    }
}
