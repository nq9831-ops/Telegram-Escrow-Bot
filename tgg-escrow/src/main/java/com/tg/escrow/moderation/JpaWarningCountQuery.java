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
package com.tg.escrow.moderation;

import com.tg.escrow.common.TggException;

import java.util.List;

/**
 * {@link WarningCountQuery} 的 JPA 实现——读 {@code warnings} 表并<b>跨群累加</b>。
 *
 * <p>刻意在 Java 侧求和而不是写 {@code sum()} 聚合 JPQL：本表行数按「群 × 被警告用户」量级，
 * 单人命中极少，Java 求和可移植性更好（避免聚合返回类型在各方言下的差异）。
 */
public final class JpaWarningCountQuery implements WarningCountQuery {

    private final WarningRepo repository;

    public JpaWarningCountQuery(WarningRepo repository) {
        if (repository == null) {
            throw new TggException("警告计数：未提供仓储");
        }
        this.repository = repository;
    }

    @Override
    public int warnCountOf(long userId) {
        List<WarningRecord> rows = repository.findByUserId(userId);
        int total = 0;
        for (WarningRecord row : rows) {
            total += row.warnCount;
        }
        return total;
    }
}
