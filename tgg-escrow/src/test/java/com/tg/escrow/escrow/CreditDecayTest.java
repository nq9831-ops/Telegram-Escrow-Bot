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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 轻量时间衰减（④ 差距 2）：久未活跃的账号，其历史活跃不该与活跃账号同权。
 *
 * <h2>这一层必须能被用户看见</h2>
 * <p>因子 &lt; 1 时 {@link CreditBreakdown#decayFactor()} 会带出来、渲染层负责披露——
 * 否则用户发现"活跃天数怎么变短了"却查不到原因，比不衰减更糟。
 */
class CreditDecayTest {

    private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");
    private static final CreditDecay POLICY =
            CreditDecay.of(365, 180, new BigDecimal("0.9"), new BigDecimal("0.3"));

    @Test
    @DisplayName("宽限期内不衰减；超出后按步长打折；远久活跃夹在下限")
    void factorStepsDown() {
        assertThat(POLICY.factorFor(NOW.minus(java.time.Duration.ofDays(365)), NOW))
                .as("宽限期边界内应满额").isEqualByComparingTo("1");
        assertThat(POLICY.factorFor(NOW.minus(java.time.Duration.ofDays(366)), NOW))
                .as("刚超出宽限 → 打一次折").isEqualByComparingTo("0.9");
        assertThat(POLICY.factorFor(NOW.minus(java.time.Duration.ofDays(546)), NOW))
                .as("再超一个步长 → 再打一次").isEqualByComparingTo("0.81");
        assertThat(POLICY.factorFor(NOW.minus(java.time.Duration.ofDays(5000)), NOW))
                .as("无论多久都不低于下限——历史成就不该被一笔抹掉").isEqualByComparingTo("0.3");
    }

    @Test
    @DisplayName("无活跃史 / 未来时刻 → 不打折（无从衰减）")
    void noDecayWithoutHistory() {
        assertThat(POLICY.factorFor(null, NOW)).isEqualByComparingTo("1");
        assertThat(POLICY.factorFor(NOW.plus(java.time.Duration.ofDays(10)), NOW))
                .as("时钟回拨或数据异常时不该倒扣").isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("参数非法 → 构造期抛（宽限负 / 步长 <1 / 因子越界 / 下限越界）")
    void rejectsBadPolicy() {
        assertThatThrownBy(() -> CreditDecay.of(-1, 180, new BigDecimal("0.9"), new BigDecimal("0.3")))
                .isInstanceOf(EscrowException.class);
        assertThatThrownBy(() -> CreditDecay.of(365, 0, new BigDecimal("0.9"), new BigDecimal("0.3")))
                .isInstanceOf(EscrowException.class);
        assertThatThrownBy(() -> CreditDecay.of(365, 180, BigDecimal.ONE, new BigDecimal("0.3")))
                .as("因子 1 等于不衰减，写它只说明配置没想清楚").isInstanceOf(EscrowException.class);
        assertThatThrownBy(() -> CreditDecay.of(365, 180, new BigDecimal("0.9"), new BigDecimal("1.5")))
                .isInstanceOf(EscrowException.class);
    }

    @Test
    @DisplayName("接入评分：久未活跃者活跃维度被折算，且拆解带出因子供披露")
    void staleAccountScoresLowerAndDisclosesFactor() {
        // 活跃跨度 501 天（该维度已饱和=满分），但最近活跃在 1000 天前 → 衰减后不再饱和，分差可辨
        TraderStats stale = new TraderStats(1L, 10, 0, 10, 0, 0,
                NOW.minus(java.time.Duration.ofDays(1500)), NOW.minus(java.time.Duration.ofDays(1000)),
                Map.of(2L, 5, 3L, 5));
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        TraderCreditService withDecay = new TraderCreditService(
                CreditScore.defaults(), new BigDecimal("0.6"), POLICY, clock);
        TraderCreditService noDecay = new TraderCreditService();

        CreditBreakdown decayed = withDecay.breakdownOf(stale);

        assertThat(decayed.decayFactor()).isLessThan(BigDecimal.ONE);
        assertThat(decayed.activeDays()).isLessThan(noDecay.breakdownOf(stale).activeDays());
        assertThat(withDecay.scoreOf(stale))
                .as("久未活跃的账号评分应低于不衰减口径")
                .isLessThan(noDecay.scoreOf(stale));
        assertThat(noDecay.breakdownOf(stale).decayFactor())
                .as("不启用衰减时因子恒 1——既有口径零漂移").isEqualByComparingTo("1");
    }
}
