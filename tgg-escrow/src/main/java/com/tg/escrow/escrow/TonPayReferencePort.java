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
import java.time.Instant;
import java.util.Optional;

/**
 * TON Pay 支付引用（ET-31/32）——登记（前端 createTonPayTransfer 后）与 webhook 结算痕迹。
 *
 * <p>承载 webhook 六步验证的持久面：reference→订单匹配（第 3 步）、防重复（第 4 步）、
 * 结算定格 + txHash（第 6 步）。
 */
public interface TonPayReferencePort {

    /** 登记支付引用（幂等：同 reference 重复登记忽略——先登记者为准）。 */
    void register(long orderId, String reference, String bodyBase64Hash);

    /** 解析：reference → 订单 ID（未知 = empty——webhook 收到未登记引用的正常场景）。 */
    Optional<Long> resolveOrderId(String reference);

    /** 该 reference 是否已结算（防重：未登记返回 false）。 */
    boolean isSettled(String reference);

    /** 结算定格：记 txHash、金额、币种与时间（六步第 6 步的落点）。 */
    void markSettled(String reference, String txHash, BigDecimal amount, String currency,
                     Instant at);
}
