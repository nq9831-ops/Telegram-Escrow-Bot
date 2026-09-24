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
 * 订单查询端口（T2 状态查询的读侧）——按订单号取回订单。
 *
 * <h2>为什么不并进 {@link EscrowOrderStore}</h2>
 * <p>两者是两套语义：{@code store} 管<b>写入契约</b>（保存失败必须抛、不得返回 null——
 * 因为"创建成功"的谎报代价最高）；本端口管<b>读取语义</b>（查不到是正常结果，返回
 * {@link Optional#empty()}，由调用方决定文案）。并在一起会让"落单结果未知时该怎么办"
 * 与"查无此单该怎么表达"相互污染。
 *
 * <p>抽成端口同样为了可测：命令层不碰 Spring Data，用内存实现即可穷举。
 */
public interface EscrowOrderLookupPort {

    /**
     * 按订单号查询。
     *
     * @param orderId 订单号
     * @return 命中的订单；不存在时为 {@link Optional#empty()}（<b>不是</b>异常）
     */
    Optional<EscrowOrder> byId(long orderId);
}
