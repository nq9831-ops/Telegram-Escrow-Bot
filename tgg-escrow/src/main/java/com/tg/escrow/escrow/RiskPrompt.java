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
 * 交易前风险提示（ET-34：创建交易前<b>强制展示</b>）。
 *
 * <p>按 {@link AmountTierPolicy} 分档给提示强度：正常→轻提示、二次确认→强调二次确认、
 * 额外验证→强警示（含风险要点）。文案说人话（SPEC 7：不用技术术语），金额原样展示。
 */
public final class RiskPrompt {

    private RiskPrompt() {
    }

    /**
     * 生成该金额对应的风险提示。
     *
     * @param amount 交易金额
     * @param policy 分层策略（决定提示强度）
     */
    public static String forAmount(BigDecimal amount, AmountTierPolicy policy) {
        if (amount == null || policy == null) {
            throw new EscrowException("风险提示：金额与分层策略均不可为空");
        }
        return switch (policy.tierOf(amount)) {
            case NORMAL -> "你将支付 " + amount + "，请核对对方身份后确认发起。";
            case CONFIRM -> "金额 " + amount + " 属中额交易，需要你再次二次确认——请核对对方身份与商品描述。";
            case EXTRA -> "⚠️ 大额交易 " + amount + " 需要额外验证。这是高风险场景——"
                    + "请务必确认对方身份与交付约定，有疑问请先走争议流程。";
        };
    }
}
