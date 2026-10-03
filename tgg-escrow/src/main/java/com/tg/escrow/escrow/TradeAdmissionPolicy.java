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

import java.time.Duration;

/**
 * 交易准入策略（原文档第六部分核心规则 <b>T23 单笔限制</b> / <b>T24 单笔冷却期</b>）。
 *
 * <p>数值全部由部署者给定——代码不内置业务默认值。本类只负责<b>构造期校验</b>，
 * 把「并发上限写成 0」这种会令门禁永久拒绝一切交易的配置，拦在启动前而不是运行中。
 *
 * @param maxConcurrentTrades 同一发起人同时进行中的交易上限（≥ 1）
 * @param cooldown            交易完成后的冷却期（非负；{@link Duration#ZERO} 表示不冷却）
 */
public record TradeAdmissionPolicy(int maxConcurrentTrades, Duration cooldown) {

    public TradeAdmissionPolicy {
        if (maxConcurrentTrades < 1) {
            throw new EscrowException("交易准入：并发上限必须 ≥ 1，实为 " + maxConcurrentTrades
                    + "——上限为 0 会让门禁永久拒绝一切交易");
        }
        if (cooldown == null) {
            throw new EscrowException("交易准入：冷却期未提供");
        }
        if (cooldown.isNegative()) {
            throw new EscrowException("交易准入：冷却期为负（" + cooldown + "）");
        }
    }
}
