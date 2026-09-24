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
 * 新账号交易限制（ET-66：新账号短期内大量交易触发风控）。
 *
 * <p>两条件<b>同时满足</b>才限制：<b>新账号</b>（{@code 账号年龄 < ageThresholdDays}，严格小于）
 * 且 <b>短期爆发</b>（{@code 近期交易数 > burstThreshold}，严格大于）。单条件不误伤：
 * 老账号爆发可能是活动促销、新账号正常节奏是正常新人。纯函数、无状态。
 */
public final class NewAccountLimitPolicy {

    private NewAccountLimitPolicy() {
    }

    /**
     * 是否限制该账号发起新交易。
     *
     * @param accountAgeDays    账号年龄（天，不得为负）
     * @param recentTradeCount  近期交易数（不得为负）
     * @param ageThresholdDays  新账号判定阈值（天，必须为正；年龄严格小于才是新）
     * @param burstThreshold    爆发判定阈值（必须为正；交易数严格大于才是爆发）
     */
    public static boolean isRestricted(int accountAgeDays, int recentTradeCount,
                                       int ageThresholdDays, int burstThreshold) {
        if (accountAgeDays < 0 || recentTradeDays(recentTradeCount)) {
            throw new EscrowException("新账号风控：计数不得为负");
        }
        if (ageThresholdDays <= 0 || burstThreshold <= 0) {
            throw new EscrowException("新账号风控：阈值必须为正");
        }
        return accountAgeDays < ageThresholdDays && recentTradeCount > burstThreshold;
    }

    private static boolean recentTradeDays(int recentTradeCount) {
        return recentTradeCount < 0;
    }
}
