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

import com.tg.escrow.common.TggException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

/** {@link TonPayReferencePort} 的 JPA 实现（register 幂等；markSettled 仅生效一次）。 */
public final class JpaTonPayReferenceStore implements TonPayReferencePort {

    private final TonPayReferenceRepository repository;

    public JpaTonPayReferenceStore(TonPayReferenceRepository repository) {
        if (repository == null) {
            throw new TggException("TON Pay 引用：仓库不可为空");
        }
        this.repository = repository;
    }

    @Override
    public void register(long orderId, String reference, String bodyBase64Hash) {
        if (reference == null || reference.isBlank()) {
            throw new TggException("TON Pay 引用：reference 不可为空");
        }
        if (repository.findByReference(reference).isPresent()) {
            return;   // 幂等：先登记者为准
        }
        repository.save(new TonPayReferenceRow(orderId, reference, bodyBase64Hash));
    }

    @Override
    public Optional<Long> resolveOrderId(String reference) {
        return repository.findByReference(reference).map(TonPayReferenceRow::getOrderId);
    }

    @Override
    public boolean isSettled(String reference) {
        return repository.findByReference(reference)
                .map(row -> row.getSettledAt() != null)
                .orElse(false);
    }

    @Override
    public void markSettled(String reference, String txHash, BigDecimal amount, String currency,
                            Instant at) {
        repository.findByReference(reference).ifPresent(row -> {
            if (row.getSettledAt() != null) {
                return;   // 只定格一次（webhook 重放不覆盖首笔痕迹）
            }
            row.markSettled(txHash, amount, currency, at);
            repository.save(row);
        });
    }
}
