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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link CreditScore} 的构造期校验与默认口径（④ 差距 1：权重配置化的 fail-fast 面）。
 *
 * <h2>为什么构造期就校验</h2>
 * <p>权重来自配置（{@code tgg.credit.weights}），最常见的配置事故是「5 项加起来不等于 1」——
 * 等于 1 时每分都是五维的加权；不等于 1 时分数会系统性偏移（比如总和 0.8 的配置永远给不出
 * 80 分以上）。这类错误必须<b>启动即炸</b>，而不是让运营在几周后发现"怎么分数都偏低"。
 */
class CreditScoreTest {

    private static BigDecimal[] weights(String... values) {
        return List.of(values).stream().map(BigDecimal::new).toArray(BigDecimal[]::new);
    }

    @Test
    @DisplayName("defaults() 保持现口径：0.30/0.25/0.20/0.15/0.10（零历史 40 分不变）")
    void defaultsKeepCurrentBehavior() {
        CreditScore score = CreditScore.defaults();

        // 零历史：completed=0（反向维度满分）、争议 0、好评 0、对手 0、活跃 0 天
        // → 0.30*0 + 0.25*1 + 0.20*0 + 0.15*1 + 0.10*0 = 40（既有行为，不得漂移）
        assertThat(score.compute(0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0))
                .isEqualTo(40);
    }

    @Test
    @DisplayName("权重总和 ≠ 1 → 构造期抛（最常见的配置事故，启动即炸）")
    void rejectsWeightsNotSummingToOne() {
        assertThatThrownBy(() -> new CreditScore(weights("0.30", "0.25", "0.20", "0.15", "0.05")))
                .isInstanceOf(EscrowException.class)
                .hasMessageContaining("总和");
    }

    @Test
    @DisplayName("不是 5 项 / 项越界 [0,1] → 构造期抛")
    void rejectsWrongShape() {
        assertThatThrownBy(() -> new CreditScore(weights("0.5", "0.5")))
                .as("五维模型就是 5 项，多一项少一项都是口径错误")
                .isInstanceOf(EscrowException.class);
        assertThatThrownBy(() -> new CreditScore(weights("1.2", "-0.2", "0", "0", "0")))
                .as("负权重没有业务语义；>1 会让单维越表")
                .isInstanceOf(EscrowException.class);
    }

    @Test
    @DisplayName("自定义权重生效：全押完成数时，完成数维度饱和 → 100 分")
    void customWeightsTakeEffect() {
        CreditScore allCompleted = new CreditScore(weights("1", "0", "0", "0", "0"));

        // completed=50（饱和）→ 1.0 * 1 = 100
        assertThat(allCompleted.compute(50, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0))
                .isEqualTo(100);
        // completed=0 → 0 分（其他维度权重为 0）
        assertThat(allCompleted.compute(0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0))
                .isEqualTo(0);
    }
}
