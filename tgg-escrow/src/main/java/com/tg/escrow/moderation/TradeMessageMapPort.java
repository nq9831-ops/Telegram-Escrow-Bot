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

import java.util.Optional;

/** 交易消息映射端口（原地编辑的持久化前提，12 材料）：tradeId → 最新 messageId。 */
public interface TradeMessageMapPort {

    /** 消息定位（chatId + messageId）。 */
    record MessageRef(long chatId, long messageId) {
    }

    /** 保存/覆盖该交易的最新消息定位。 */
    void save(long tradeId, long chatId, long messageId);

    /** 查询该交易的最新消息定位。 */
    Optional<MessageRef> find(long tradeId);
}
