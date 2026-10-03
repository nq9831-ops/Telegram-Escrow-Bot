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

/** {@link DisputeLossStore} 的 JPA 实现——绑定到 {@code dispute_losses} 表。 */
public final class JpaDisputeLossStore implements DisputeLossStore {

    private final DisputeLossRepository repository;

    public JpaDisputeLossStore(DisputeLossRepository repository) {
        if (repository == null) {
            throw new EscrowException("败诉台账存储：未提供仓储");
        }
        this.repository = repository;
    }

    @Override
    public Optional<DisputeLoss> find(long orderId) {
        return repository.findById(orderId);
    }

    @Override
    public DisputeLoss save(DisputeLoss loss) {
        if (loss == null) {
            throw new EscrowException("败诉台账存储：不得保存 null 记录");
        }
        DisputeLoss saved = repository.save(loss);
        if (saved == null) {
            throw new EscrowException("败诉台账存储：保存返回 null，结果未知");
        }
        return saved;
    }

    @Override
    public int countByLoser(long loserUserId) {
        return repository.countByLoserUserId(loserUserId);
    }
}
