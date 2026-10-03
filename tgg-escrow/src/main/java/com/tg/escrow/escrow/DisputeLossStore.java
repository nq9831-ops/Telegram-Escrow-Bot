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
 * 争议败诉台账存储端口（ET-61）。
 *
 * <h2>实现契约</h2>
 * <ul>
 *   <li>保存失败必须<b>抛异常</b>，不得静默返回；</li>
 *   <li>不得返回 {@code null}；查不到返回 {@link Optional#empty()}。</li>
 * </ul>
 */
public interface DisputeLossStore {

    /** 按订单号查败诉记录（幂等判据）；不存在返回空。 */
    Optional<DisputeLoss> find(long orderId);

    /** 保存败诉记录（新建）。 */
    DisputeLoss save(DisputeLoss loss);

    /** 某人名下的败诉次数（无记录为 0）。 */
    int countByLoser(long loserUserId);
}
