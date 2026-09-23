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
package com.tg.escrow.chain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 单个数据源对某地址余额的一次观测。
 *
 * <p><b>为什么观测要带确认数</b>：链上的"余额"不是一个瞬时事实，而是一个随最新区块推进的
 * 状态。同一地址在不同确认深度下可能给出不同答案（重组、未确认交易）。所以"读到 100"
 * 本身不构成事实，"在第 N 个确认深度上读到 100"才是。
 *
 * <p>不校验余额是否非负：链上确实可能出现异常状态（合约余额为 0、地址不存在），
 * 判定它是否合理是调用方的事，本记录只承载观测。
 */
public record BalanceObservation(String source, BigDecimal balance, int confirmations,
                                 Instant observedAt) {

    public BalanceObservation {
        if (source == null || source.isBlank()) {
            throw new ChainUnavailableException("观测缺少来源标识");
        }
        if (balance == null) {
            throw new ChainUnavailableException("观测缺少余额值（来源 " + source + "）");
        }
        if (confirmations < 0) {
            throw new ChainUnavailableException(
                    "观测的确认数为负（来源 " + source + "，值 " + confirmations + "）");
        }
        if (observedAt == null) {
            throw new ChainUnavailableException("观测缺少时刻（来源 " + source + "）");
        }
    }
}
