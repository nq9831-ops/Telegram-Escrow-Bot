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
 * 争议会话存储端口——陈述与证据窗口的持久化（重启不丢）。
 * 契约：save 失败必抛、不得返回 null；find 查不到 = {@link Optional#empty()}。
 */
public interface DisputeSessionStore {

    /** 按订单号查争议会话；不存在返回空。 */
    Optional<DisputeSession> find(long orderId);

    /** 保存（新建或更新）；返回已保存会话，不得为 null。 */
    DisputeSession save(DisputeSession session);
}
