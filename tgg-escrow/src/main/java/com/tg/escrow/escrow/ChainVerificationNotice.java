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
 * 资金流向验证推送（ET-59/70）：交易完成后推送链上交易哈希供用户自行验证，
 * 并随推送周知官方声明（不通过评论发放奖励——Comment 钓鱼防御）。
 */
public final class ChainVerificationNotice {

    private ChainVerificationNotice() {
    }

    /**
     * 生成推送文案。
     *
     * @param txHash 链上交易哈希（不得空白）
     */
    public static String forTx(String txHash) {
        if (txHash == null || txHash.isBlank()) {
            throw new EscrowException("资金流向推送：交易哈希不得空白");
        }
        return "✅ 资金流向已上链，交易哈希：" + txHash.trim()
                + "——你可在区块浏览器自行验证。提示：官方不通过评论发放任何奖励，谨防钓鱼。";
    }
}
