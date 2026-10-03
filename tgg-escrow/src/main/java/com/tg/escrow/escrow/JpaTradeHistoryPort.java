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
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@link TradeHistoryPort} 的 JPA 实现——按「用户 + 状态」在库内聚合准入事实。
 *
 * <h2>状态分类不硬编码，由枚举派生</h2>
 * <p>{@link #TERMINAL_STATE_NAMES} 直接来自 {@link EscrowOrder.State}，再用「非终态即进行中」
 * 反推出 {@link #ACTIVE_STATE_NAMES}。这样将来加状态（例如新增 {@code EXPIRED}）时，
 * 分类会自动跟上——否则新增状态会被静默算成「进行中」或「已完成」中的错误一侧，
 * 直接影响 T23/T24 的判定。
 *
 * <h2>三个事实对应三条规则</h2>
 * <ul>
 *   <li>{@code activeTradeCount} → T23 单笔限制；</li>
 *   <li>{@code lastCompletedTradeAt} → T24 单笔冷却期（取终态订单 {@code updatedAt} 最大值）；</li>
 *   <li>{@code hasUnresolvedDispute} → T25 争议期间冻结。</li>
 * </ul>
 */
public final class JpaTradeHistoryPort implements TradeHistoryPort {

    /** 终态：资金已有归属，不再占用并发额度。 */
    private static final Set<String> TERMINAL_STATE_NAMES = Set.of(
            EscrowOrder.State.RELEASED.name(),
            EscrowOrder.State.REFUNDED.name(),
            EscrowOrder.State.CANCELLED.name());

    /** 进行中：由「非终态」反推，避免新增状态时分类漂移。 */
    private static final Set<String> ACTIVE_STATE_NAMES = Arrays.stream(EscrowOrder.State.values())
            .map(Enum::name)
            .filter(name -> !TERMINAL_STATE_NAMES.contains(name))
            .collect(Collectors.toUnmodifiableSet());

    private static final Set<String> DISPUTED_STATE_NAMES =
            Set.of(EscrowOrder.State.DISPUTED.name());

    private final EscrowOrderRepository repository;

    public JpaTradeHistoryPort(EscrowOrderRepository repository) {
        if (repository == null) {
            throw new com.tg.escrow.common.EscrowException("历史查询：未提供仓储");
        }
        this.repository = repository;
    }

    @Override
    public TradeAdmissionContext snapshotOf(long subjectId) {
        long activeTradeCount = repository.countByUserAndStates(subjectId, ACTIVE_STATE_NAMES);
        Instant lastCompletedTradeAt =
                repository.findMaxUpdatedAtByUserAndStates(subjectId, TERMINAL_STATE_NAMES);
        boolean hasUnresolvedDispute =
                repository.countByUserAndStates(subjectId, DISPUTED_STATE_NAMES) > 0;

        return new TradeAdmissionContext(subjectId, (int) activeTradeCount,
                lastCompletedTradeAt, hasUnresolvedDispute);
    }
}
