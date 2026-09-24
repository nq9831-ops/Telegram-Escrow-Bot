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

/**
 * 兜底裁决（ET-47）：投票截止未达阈值（无共识）时的预设自动裁决。
 *
 * <h2>为什么默认退款</h2>
 * <p>资金未获"放行共识"时，退回买方是保守方向——宁可交易不成立，不可在无共识下放款。
 * 部署者可改为 {@link Action#RELEASE}，但那会把"无人表态"解释为"放行"，需运营者自行担责。
 *
 * <p>本类只回答 {@code INSUFFICIENT 时怎么办}——明确裁决（RELEASE/REFUND）不归它管，
 * 它对明确裁决返回 {@link Action#NONE}（不介入）。纯函数、无状态。
 */
public final class FallbackSettle {

    /** 兜底动作。 */
    public enum Action {
        /** 不介入。 */
        NONE,
        /** 自动放款给卖方。 */
        RELEASE,
        /** 自动退款给买方。 */
        REFUND
    }

    private final Action fallback;

    /**
     * @param fallback 未达阈值时的兜底动作（不可为 {@code null} 或 {@link Action#NONE}）
     */
    public FallbackSettle(Action fallback) {
        if (fallback == null || fallback == Action.NONE) {
            throw new EscrowException("兜底裁决：必须指定一个真实的兜底动作（RELEASE 或 REFUND）");
        }
        this.fallback = fallback;
    }

    /**
     * 给定计票结果，兜底应执行的动作。
     *
     * @param outcome {@link MemberVote} 的判定结果
     * @return {@code INSUFFICIENT} 时返回兜底动作；明确裁决返回 {@link Action#NONE}
     */
    public Action actionFor(MemberVote.Outcome outcome) {
        if (outcome == null) {
            throw new EscrowException("兜底裁决：计票结果不可为空");
        }
        return outcome == MemberVote.Outcome.INSUFFICIENT ? fallback : Action.NONE;
    }
}
