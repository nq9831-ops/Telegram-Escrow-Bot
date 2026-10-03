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

import java.util.List;
import java.util.Optional;

/**
 * 订单查询读侧端口。与 {@link EscrowOrderStore} 分开：store 管写入契约（失败必抛），
 * 本端口管读取语义（查不到 = {@link Optional#empty()}，正常结果）。抽端口以便命令层不碰 Spring Data。
 */
public interface EscrowOrderLookupPort {

    /** 按订单号查询；不存在返回 {@link Optional#empty()}（不是异常）。 */
    Optional<EscrowOrder> byId(long orderId);

    /**
     * 取某用户最近参与的订单（买卖任一侧），按最后更新倒序——{@code /escrow my} 的数据源。
     * 只按用户自己过滤（与「只查自己」同一隐私取向）。
     *
     * @param limit 最多返回条数；非正数按 1 处理（防静默空操作）
     */
    List<EscrowOrder> recentFor(long userId, int limit);
}
