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

/**
 * 邀请的只读视图（「已接受再打开」入口的数据形态，2026-10-02）。
 *
 * <p>{@code state} 语义：{@link #PENDING}（可接受）／{@link #ACCEPTED}（已接单，携带
 * {@code orderId}——Mini App 据此直接进资金操作区，免撞二次 accept 的 409）／
 * {@link #EXPIRED}（过期未接）。令牌无效时服务层给 {@code Optional.empty()}——不存在即无视图。
 */
public record TradeInviteView(String state, Long orderId, long buyerUserId,
                              BigDecimal amount, String currency, Instant expiresAt) {

    public static final String PENDING = "PENDING";
    public static final String ACCEPTED = "ACCEPTED";
    public static final String EXPIRED = "EXPIRED";
}
