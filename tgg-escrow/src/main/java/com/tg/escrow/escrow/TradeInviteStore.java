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
 * 邀请存储端口——待接受邀请的落库/查回出口。
 *
 * <p>与 {@link EscrowOrderStore} 同一手法：把「存到哪」抽成注入点，让服务与聚合
 * 在<b>纯本地</b>被穷举测试；真实实现（JPA 表）只替换这一个端口。
 *
 * <h2>实现契约</h2>
 * <ul>
 *   <li><b>save</b> 保存失败必须抛异常，不得静默返回；并发版本冲突须转译为
 *       {@link ConcurrentOrderUpdateException}（技术异常不越界）；不得返回 {@code null}。</li>
 *   <li><b>byToken</b> 查不到是<b>正常结果</b>（{@link Optional#empty()}），不是异常；
 *       令牌空白时同样返回空而非抛。</li>
 * </ul>
 */
public interface TradeInviteStore {

    /**
     * 保存（新建或更新）邀请。
     *
     * @param invite 待保存邀请，不得为 {@code null}
     * @return 已保存邀请（可回填 ID），不得为 {@code null}
     */
    TradeInvite save(TradeInvite invite);

    /**
     * 按一次性令牌查回邀请。
     *
     * @param token 邀请令牌
     * @return 命中邀请；无匹配或令牌空白时为 {@link Optional#empty()}
     */
    Optional<TradeInvite> byToken(String token);
}
