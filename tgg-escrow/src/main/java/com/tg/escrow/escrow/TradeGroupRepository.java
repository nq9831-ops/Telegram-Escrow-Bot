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

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 交易群绑定的 Spring Data 仓储。主键即交易号（一交易一群）。
 *
 * <p>没有自定义查询方法：群侧的全部读写都经 {@link TradeGroupStore}，
 * 反查（{@code chatId → tradeId}）是后续项，按需再加。
 */
public interface TradeGroupRepository extends JpaRepository<TradeGroup, Long> {

    /** 按群 chat ID 反查（惰性归档入口）。 */
    java.util.Optional<TradeGroup> findByChatId(Long chatId);
}
