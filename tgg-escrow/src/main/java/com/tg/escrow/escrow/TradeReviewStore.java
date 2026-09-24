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
 * 交易评价存储端口——把 {@link TradeReview} 的「谁评过」从内存搬到可持久化的事实。
 *
 * <h2>为什么必须有它</h2>
 * <p>{@link TradeReview} 是<b>有状态</b>守卫（内部 Set）。只留在内存的话，
 * 进程重启即丢「已评价」事实 → 同一方可重复刷评价。本端口负责读写这条事实。
 *
 * <h2>实现契约</h2>
 * <ul>
 *   <li>{@code record} 失败必须<b>抛异常</b>，不得静默返回——否则上层会以为评价已落库；</li>
 *   <li>重复评价（同订单同评价人）必须被拒——由数据库唯一索引兜底并在适配器转译为领域异常；</li>
 *   <li>{@code reviewersOf} 查不到是<b>正常结果</b>（空集合），不是异常。</li>
 * </ul>
 */
public interface TradeReviewStore {

    /**
     * 落一条评价。
     *
     * @param orderId    订单号
     * @param reviewerId 评价人
     * @param score      评分（1–5；范围由 {@link TradeReview} 守卫）
     * @param createdAt  评价时刻
     * @throws com.tg.escrow.common.EscrowException 该方已评价过、或落库失败
     */
    void record(long orderId, long reviewerId, int score, Instant createdAt);

    /**
     * 取某订单已评价过的当事人集合。
     *
     * @param orderId 订单号
     * @return 已评价人 ID 集合；无评价时为空集合（<b>不是</b>异常）
     */
    Set<Long> reviewersOf(long orderId);
}
