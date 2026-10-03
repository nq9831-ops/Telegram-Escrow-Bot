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
 * 邀请存储端口。契约：save 失败必抛（并发冲突转 {@link ConcurrentOrderUpdateException}）、
 * 不得返回 null；byToken 查不到 = {@link Optional#empty()}（正常结果，令牌空白同样返回空）。
 */
public interface TradeInviteStore {

    /** 保存（新建或更新）邀请；返回已保存邀请（可回填 ID），不得为 null。 */
    TradeInvite save(TradeInvite invite);

    /** 按一次性令牌查回；无匹配或令牌空白返回 {@link Optional#empty()}。 */
    Optional<TradeInvite> byToken(String token);
}
