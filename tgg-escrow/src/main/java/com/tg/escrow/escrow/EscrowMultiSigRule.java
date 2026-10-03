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

import java.util.Set;

/**
 * 裁决多签规则的单一定义处。规则：①必须包含联邦（「担保只认联邦」）；②有效组合 =
 * 联邦+买方 或 联邦+卖方；③<b>买方+卖方无效</b>——双方串通即自行放款/退款，多签形同虚设
 * （允许它 = 担保退化为双方自管）。
 * 只判签名方集合是否满足规则，不判签名真伪（验签是调用方前提）。
 */
public final class EscrowMultiSigRule {

    /** 多签参与方。 */
    public enum Party {
        /** 联邦仲裁节点（裁决必含）。 */
        FEDERATION,
        /** 买方。 */
        BUYER,
        /** 卖方。 */
        SELLER
    }

    /** 裁决所需的最少签名方数（即 "2/3" 里的 2）。 */
    public static final int REQUIRED_SIGNERS = 2;

    private EscrowMultiSigRule() {
    }

    /** 判定签名方集合是否满足裁决条件；不足阈值或 null 返回 false（「签名不够」是正常业务结果）。 */
    public static boolean satisfies(Set<Party> signers) {
        if (signers == null || signers.size() < REQUIRED_SIGNERS) {
            return false;
        }
        return signers.contains(Party.FEDERATION);
    }
}
