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

import java.math.BigDecimal;

/**
 * 发起一笔新交易的请求——只装「要什么」，不装「能不能」。
 *
 * <p>能否发起由 {@link EscrowTradeService} 经 {@link TradeAdmissionGate} 判定，
 * 本 record 只做<b>请求自身的合法性</b>校验（资金字段不容含糊、禁止自交易）。
 * 把两者分开：前者是业务策略（会变），后者是事实错误（永远不该接受）。
 *
 * @param buyerId  买方 ID（也是准入检查的对象）
 * @param sellerId 卖方 ID
 * @param amount   交易金额（必须为正）
 * @param currency 币种（须落在 TON / USDT(TON 链) 白名单内；大小写与空白会归一）
 */
public record TradeInitiationRequest(long buyerId, long sellerId, BigDecimal amount,
                                     String currency) {

    public TradeInitiationRequest {
        if (buyerId == sellerId) {
            // 自交易让担保失去意义：买方锁仓又自己确认收货，全程无对手方风险
            throw new EscrowException("交易创建：买方与卖方不得为同一人（" + buyerId + "）——自交易会让担保失去意义");
        }
        if (amount == null) {
            throw new EscrowException("交易创建：金额未提供");
        }
        if (amount.signum() <= 0) {
            throw new EscrowException("交易创建：金额必须为正数，实为 " + amount);
        }
        if (currency == null || currency.isBlank()) {
            throw new EscrowException("交易创建：币种未提供");
        }
        // 币种白名单（Wave 0）：TON / USDT(TON 链) 之外一律拒，并归一为规范形式。
        // 收口在此一处，命令层（TradeCommandHandler）与 /api/trade/create 同受约束。
        currency = TradeCurrency.requireSupported(currency).name();
    }
}
