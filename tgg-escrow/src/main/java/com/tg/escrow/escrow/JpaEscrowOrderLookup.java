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

import java.util.Optional;

/**
 * {@link EscrowOrderLookupPort} 的 JPA 实现——按主键查 {@code escrow_orders}。
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
}
