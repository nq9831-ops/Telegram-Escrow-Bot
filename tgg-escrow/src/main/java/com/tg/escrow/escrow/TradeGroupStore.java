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

import java.util.Optional;

/**
 * 交易群存储端口——群生命周期状态的持久化（重启不丢）。
 * 契约：save 失败必抛、不得返回 null；查不到 = {@link Optional#empty()}。
 */
public interface TradeGroupStore {

    /** 按交易号查群绑定；不存在返回空。 */
    Optional<TradeGroup> find(long tradeId);

    /** 按群 chat ID 反查（惰性归档结算入口）；不存在返回空。 */
    Optional<TradeGroup> findByChatId(long chatId);

    /** 保存群绑定（新建或更新）；返回已保存绑定，不得为 null。 */
    TradeGroup save(TradeGroup group);
}
