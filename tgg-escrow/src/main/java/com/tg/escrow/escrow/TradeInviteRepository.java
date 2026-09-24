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

import java.util.Optional;

/**
 * 待接受邀请的 Spring Data 仓储。
 *
 * <p>只加一条按令牌查回的派生方法——接单入口拿到的就是令牌（来自已验签的
 * {@code start_param}），按令牌定位是本流程唯一的读路径。
 */
public interface TradeInviteRepository extends JpaRepository<TradeInvite, Long> {

    /** 按一次性令牌查回邀请；无匹配返回空。 */
    Optional<TradeInvite> findByToken(String token);
}
