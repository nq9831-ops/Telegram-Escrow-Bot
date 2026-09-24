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

import java.util.Optional;

/**
 * 投票资格分层（ET-41，业务冲突 1.3 的解法）。
 *
 * <h2>为什么分层</h2>
 * <p>若投票资格一律要求"完成 ≥5 笔"，新用户永远进不了投票池——投票人池会逐渐枯竭。
 * 分层给出两级缓冲：<b>观察员（≥1 笔）</b>可参与讨论但无投票权；满 5 笔起有投票权，
 * 权重随完成数递增（<b>≥20 权重 3 / ≥10 权重 2 / ≥5 权重 1</b>）。
 *
 * <h2>权重只定义一次</h2>
 * <p>该分层同时是排名机制（ET-75/76）的投票权重来源——<b>一处定义、两处引用</b>，
 * 避免"投票权重 3/2/1"与"钻石/金牌/银牌 3/2/1"漂移成两套数。
 *
 * <p>纯函数、无状态。边界语义定死：<b>含下限</b>（≥20 即核心）。
 */
public final class VoterRegistry {

    /** 投票人层级。 */
    public enum Tier {
        /** 核心投票人（≥20 笔），权重 3。 */
        CORE(3),
        /** 资深投票人（≥10 笔），权重 2。 */
        SENIOR(2),
        /** 普通投票人（≥5 笔），权重 1。 */
        REGULAR(1),
        /** 观察员（≥1 笔）：可参与讨论，无投票权。 */
        OBSERVER(0);

        private final int weight;

        Tier(int weight) {
            this.weight = weight;
        }

        /** 投票权重（观察员为 0）。 */
        public int weight() {
            return weight;
        }
    }

    private VoterRegistry() {
    }

    /**
     * 按完成笔数判定层级。
     *
     * @param completedCount 完成交易笔数（不得为负——数据异常 fail-closed）
     * @return 层级；0 笔返回空（无资格，连观察员都不是）
     */
    public static Optional<Tier> tierOf(int completedCount) {
        if (completedCount < 0) {
            // 负数是数据异常：不按 0 笔静默降级，把上游的错误暴露出来
            throw new EscrowException("投票资格：完成笔数为负（" + completedCount + "）——数据异常，拒绝据此判定");
        }
        if (completedCount >= 20) {
            return Optional.of(Tier.CORE);
        }
        if (completedCount >= 10) {
            return Optional.of(Tier.SENIOR);
        }
        if (completedCount >= 5) {
            return Optional.of(Tier.REGULAR);
        }
        if (completedCount >= 1) {
            return Optional.of(Tier.OBSERVER);
        }
        return Optional.empty();
    }

    /** 该层级的投票权重。 */
    public static int weightOf(Tier tier) {
        if (tier == null) {
            throw new EscrowException("投票资格：层级不可为空");
        }
        return tier.weight();
    }

    /** 该层级是否拥有投票权（观察员无）。 */
    public static boolean canVote(Tier tier) {
        return weightOf(tier) > 0;
    }
}
