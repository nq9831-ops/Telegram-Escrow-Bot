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
import com.tg.escrow.escrow.FallbackSettle.Action;
import com.tg.escrow.escrow.MemberVote.Outcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 兜底裁决（ET-47）的行为固定测试。
 *
 * <p>语义：投票截止<b>未达阈值</b>时按预设规则自动裁决——默认<b>退款</b>（资金未获共识放行时
 * 退回买方是保守方向）。达标与否由 {@link MemberVote} 判定，本类只回答"INSUFFICIENT 时怎么办"。
 */
class FallbackSettleTest {

    @Test
    @DisplayName("默认策略：未达阈值 → 退款（保守方向）")
    void defaultFallbackIsRefund() {
        FallbackSettle s = new FallbackSettle(Action.REFUND);

        assertThat(s.actionFor(Outcome.INSUFFICIENT)).isEqualTo(Action.REFUND);
    }

    @Test
    @DisplayName("可配置为放款兜底")
    void configurableToRelease() {
        FallbackSettle s = new FallbackSettle(Action.RELEASE);

        assertThat(s.actionFor(Outcome.INSUFFICIENT)).isEqualTo(Action.RELEASE);
    }

    @Test
    @DisplayName("已有明确裁决（RELEASE/REFUND）→ 兜底不介入（返回 NONE）")
    void settledOutcomeNotOverridden() {
        FallbackSettle s = new FallbackSettle(Action.REFUND);

        assertThat(s.actionFor(Outcome.RELEASE)).isEqualTo(Action.NONE);
        assertThat(s.actionFor(Outcome.REFUND)).isEqualTo(Action.NONE);
    }

    @Test
    @DisplayName("null 参数 → 构造/判定期 fail-closed")
    void nullArgsRejected() {
        assertThatThrownBy(() -> new FallbackSettle(null)).isInstanceOf(EscrowException.class);
        assertThatThrownBy(() -> new FallbackSettle(Action.REFUND).actionFor(null))
                .isInstanceOf(EscrowException.class);
    }
}
