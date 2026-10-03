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

/**
 * {@link FeeLedgerPort} 的 JPA 实现——幂等（同订单已存在直接返回）。
 *
 * <p>不抛契约的上游：调用方（放款收尾）已把它当旁路，但本实现本身也只在参数不合法时
 * 抛（构造期）——运行期数据库异常由调用方 catch 留痕。
 */
public final class JpaFeeLedgerStore implements FeeLedgerPort {

    private final FeeLedgerRepository repository;

    public JpaFeeLedgerStore(FeeLedgerRepository repository) {
        if (repository == null) {
            throw new TggException("费用台账：仓库不可为空");
        }
        this.repository = repository;
    }

    @Override
    public void record(Entry entry) {
        if (entry == null) {
            throw new TggException("费用台账：账目不可为空");
        }
        if (repository.existsByOrderId(entry.orderId())) {
            return;   // 幂等：重放/重试不重复入账
        }
        repository.save(new FeeLedgerRow(entry.orderId(), entry.currency(),
                entry.grossAmount(), entry.platformFee(), entry.sellerNet(),
                entry.firstOrderWaived()));
    }
}
