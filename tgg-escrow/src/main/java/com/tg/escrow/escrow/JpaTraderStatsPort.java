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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link TraderStatsPort} 的 JPA 实现——把 {@code escrow_orders} / {@code trade_reviews}
 * 聚合成 {@link TraderStats}。
 *
 * <h2>为什么把"完成"与"争议"分别用两条按状态计数的查询，而不是读全表在内存里数</h2>
 * <p>与准入门禁（T23/T24/T25）同一理由：榜单与信用摘要是<b>每次命令都会算</b>的东西，
 * 把过滤下推成 {@code count} 才不会随订单量线性变慢。这里每个数字都是一条聚合，
 * 一次 {@code of(userId)} 共 7 条小查询——对单群体量完全够用；若将来成为瓶颈，
 * 正确的下一步是物化榜单表（见端口注释），而不是把它们合成一次全表扫描。
 *
 * <h2>口径都在别处，这里只做搬运</h2>
 * <p>"完成 = RELEASED""好评 ≥ 4""争议率分母 = 参与总数"这三条口径的**说明**在
 * {@link TraderStats}，**落地**在 {@link TraderCreditService}；本类只负责把库里的字节
 * 搬成记录。唯一在此处固定的口径是 {@link #POSITIVE_MIN_SCORE}——因为过滤必须在 SQL 里做，
 * 而这个阈值又被 {@link TraderStats#positiveReviews()} 的注释引用，故在此声明并注明出处。
 */
public final class JpaTraderStatsPort implements TraderStatsPort {

    /**
     * 好评线：评价分制 1–5（见 {@link TradeReview#MIN_SCORE} / {@link TradeReview#MAX_SCORE}），
     * {@code ≥ 4} 记为好。过滤必须在 SQL 内完成，故阈值声明在此（口径说明见 {@link TraderStats}）。
     */
    public static final int POSITIVE_MIN_SCORE = 4;

    /** 状态名集合——库里存的是 {@code State.name()}（见 {@link EscrowOrder} 各 markX）。 */
    private static final List<String> RELEASED = List.of(EscrowOrder.State.RELEASED.name());
    private static final List<String> DISPUTED = List.of(EscrowOrder.State.DISPUTED.name());

    private final EscrowOrderRepository orders;
    private final TradeReviewRepository reviews;

    public JpaTraderStatsPort(EscrowOrderRepository orders, TradeReviewRepository reviews) {
        if (orders == null) {
            throw new EscrowException("交易者统计：未提供订单仓储");
        }
        if (reviews == null) {
            throw new EscrowException("交易者统计：未提供评价仓储");
        }
        this.orders = orders;
        this.reviews = reviews;
    }

    @Override
    public TraderStats of(long userId) {
        return new TraderStats(userId,
                (int) orders.countByUserAndStates(userId, RELEASED),
                (int) orders.countByUserAndStates(userId, DISPUTED),
                (int) orders.countAllByUser(userId),
                (int) reviews.countReceivedPositiveByUser(userId, POSITIVE_MIN_SCORE),
                (int) reviews.countReceivedByUser(userId),
                orders.findMinCreatedAtByUser(userId),
                orders.findMaxUpdatedAtByUser(userId),
                counterpartiesOf(userId));
    }

    @Override
    public List<TraderStats> allWithActivity() {
        Set<Long> ids = new LinkedHashSet<>(orders.distinctBuyerIds());
        ids.addAll(orders.distinctSellerIds());
        ids.removeIf(id -> id == null || id <= 0);
        List<TraderStats> all = new ArrayList<>(ids.size());
        for (Long id : ids) {
            all.add(of(id));
        }
        return List.copyOf(all);
    }

    /**
     * 合并买方视角与卖方视角的对手分布。
     *
     * <p>同一个人既当过买方对手、又当过卖方对手时会累加——多样性关心的是"跟谁做了多少笔"，
     * 与方向无关。
     */
    private Map<Long, Integer> counterpartiesOf(long userId) {
        Map<Long, Integer> merged = new HashMap<>();
        for (Object[] row : orders.countBySellerForBuyer(userId)) {
            merged.merge(((Number) row[0]).longValue(), ((Number) row[1]).intValue(), Integer::sum);
        }
        for (Object[] row : orders.countByBuyerForSeller(userId)) {
            merged.merge(((Number) row[0]).longValue(), ((Number) row[1]).intValue(), Integer::sum);
        }
        return merged;
    }
}
