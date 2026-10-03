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
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Optional;

/**
 * {@link EscrowOrderLookupPort} 的 JPA 实现——按主键查单，或按用户取最近的单。
 *
 * <p>薄转发：语义都在端口契约里（查不到返回 empty，不是异常）。Spring Data 的
 * {@code findById} 恰好就是这个语义，无需额外包装。
 */
public final class JpaEscrowOrderLookup implements EscrowOrderLookupPort {

    private final EscrowOrderRepository repository;

    public JpaEscrowOrderLookup(EscrowOrderRepository repository) {
        if (repository == null) {
            throw new EscrowException("订单查询：未提供仓储");
        }
        this.repository = repository;
    }

    @Override
    public Optional<EscrowOrder> byId(long orderId) {
        return repository.findById(orderId);
    }

    @Override
    public List<EscrowOrder> recentFor(long userId, int limit) {
        // limit 夹取到 ≥1：传 0/负数时若原样下推，会得到"查了却什么都返回不了"的静默空操作
        // ——用户看到的是"没有订单"，与"你真没有订单"无法区分。
        return repository.findByBuyerUserIdOrSellerUserIdOrderByUpdatedAtDesc(
                userId, userId, PageRequest.of(0, Math.max(limit, 1)));
    }
}
