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

import java.math.BigDecimal;

/**
 * 放款费用台账（ET-29/30）——把每笔放款的费用定格为一行账目。
 *
 * <h2>为什么需要它</h2>
 * <p>费率早已定案、核算也有（{@code FeePolicy}），但核算结果此前只出现在用户回执里、
 * <b>不落库</b>——对账时「这笔交易收了多少平台费」无从查起。本端口是这条链上唯一的
 * 持久痕迹。
 *
 * <h2>为什么是"链下账本"（不改合约）</h2>
 * <p>合约注释明确：「平台费率走链下账本（FeePolicy），链上只保证托管额全额可退/可放」——
 * 链上不做费用扣减是既定的架构决定。本端口落实"账本"的一半：可查、可对。
 *
 * <h2>失败取向</h2>
 * <p>调用方（放款收尾）把记账视作旁路：失败留痕（warn）不阻断已完成的交易——账目缺口
 * 可通过后台查询发现，它不改动资金状态。<b>幂等</b>：同订单重复记录由实现去重。
 */
public interface FeeLedgerPort {

    /**
     * 一条放款账目。
     *
     * @param orderId          订单 ID（每单至多一条）
     * @param currency         币种
     * @param grossAmount      放款总额（订单金额）
     * @param platformFee      实际收取的平台费（首单豁免时为 0）
     * @param sellerNet        卖方实收（gross − platformFee）
     * @param firstOrderWaived 是否发生首单豁免（费率非 0 但实收 0；费率本为 0 时不算豁免）
     */
    record Entry(long orderId, String currency, BigDecimal grossAmount,
                 BigDecimal platformFee, BigDecimal sellerNet, boolean firstOrderWaived) {
    }

    /** 记录一条放款账目（幂等：同订单已存在时实现应忽略重复）。 */
    void record(Entry entry);
}
