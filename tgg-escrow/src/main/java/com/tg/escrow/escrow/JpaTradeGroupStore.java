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
 * {@link TradeGroupStore} 的 JPA 实现——绑定到 {@code trade_groups} 表。
 *
 * <p>守端口契约的两条：失败抛异常不静默返回，且绝不返回 {@code null}
 * （查不到给空 {@link Optional}，不使用 {@code null} 表达"无"）。
 */
public final class JpaTradeGroupStore implements TradeGroupStore {

    private final TradeGroupRepository repository;

    public JpaTradeGroupStore(TradeGroupRepository repository) {
        if (repository == null) {
            throw new EscrowException("交易群存储：未提供仓储");
        }
        this.repository = repository;
    }

    @Override
    public Optional<TradeGroup> find(long tradeId) {
        return repository.findById(tradeId);
    }

    @Override
    public Optional<TradeGroup> findByChatId(long chatId) {
        return repository.findByChatId(chatId);
    }

    @Override
    public TradeGroup save(TradeGroup group) {
        if (group == null) {
            throw new EscrowException("交易群存储：不得保存 null 群绑定");
        }
        TradeGroup saved = repository.save(group);
        if (saved == null) {
            // Spring Data 正常不会返回 null；此处是契约防线——结果未知时不谎报成功
            throw new EscrowException("交易群存储：保存返回 null，结果未知");
        }
        return saved;
    }
}
