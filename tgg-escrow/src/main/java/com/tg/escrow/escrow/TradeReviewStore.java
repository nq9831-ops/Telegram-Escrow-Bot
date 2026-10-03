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

import java.time.Instant;
import java.util.Set;

/**
 * 交易评价存储端口——「谁评过」的持久化事实（重启不丢，防重复刷评）。
 * 契约：record 失败必抛（不得静默）；重复评价由唯一索引兜底转领域异常；reviewersOf 查不到 = 空集合（正常）。
 */
public interface TradeReviewStore {

    /** 落一条评价；该方已评价或落库失败抛 {@code EscrowException}。 */
    void record(long orderId, long reviewerId, int score, Instant createdAt);

    /** 某订单已评价的当事人集合；无评价为空集合。 */
    Set<Long> reviewersOf(long orderId);
}
