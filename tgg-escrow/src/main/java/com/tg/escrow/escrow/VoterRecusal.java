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

import java.util.Set;

/**
 * 投票人回避判定（ET-42）。
 *
 * <h2>两条回避线</h2>
 * <ol>
 *   <li><b>利益关联</b>：与买方或卖方在窗口内有过交易的用户回避；</li>
 *   <li><b>当事方本人</b>：买方与卖方自己当然回避。</li>
 * </ol>
 *
 * <p>回避是排除利益关联，<b>不是</b>禁止一切参与——无关第三方不被误伤。
 *
 * <h2>窗口语义由调用方提供</h2>
 * <p>本类只判"给定的近期对手集合里有没有买卖双方"。<b>"近期"（窗口多长）由调用方</b>
 * 按查询能力决定（如近 N 天交易对手），与 {@link TradeHistoryPort} 的取数据职责一致——
 * 判定与取数分离，同一份回避规则才能被不同数据源复用。
 *
 * <p>纯函数、无状态。
 */
public final class VoterRecusal {

    private VoterRecusal() {
    }

    /**
     * 判定某投票人是否应予回避。
     *
     * @param recentCounterparties 该投票人在窗口内的交易对手集合（不得为 {@code null}）
     * @param buyerId              争议订单买方
     * @param sellerId             争议订单卖方
     * @param voterId              候选投票人
     * @return 为买卖双方本人、或与任一方有近期交易时为 {@code true}
     * @throws EscrowException 对手集合为 {@code null}——不静默当作"无交易"放行
     */
    public static boolean isRecused(Set<Long> recentCounterparties,
                                    long buyerId, long sellerId, long voterId) {
        if (recentCounterparties == null) {
            throw new EscrowException("投票回避：近期对手集合缺失——不按无交易放行");
        }
        if (voterId == buyerId || voterId == sellerId) {
            return true;
        }
        return recentCounterparties.contains(buyerId) || recentCounterparties.contains(sellerId);
    }
}
