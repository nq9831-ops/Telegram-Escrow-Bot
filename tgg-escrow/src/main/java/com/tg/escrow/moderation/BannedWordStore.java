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

import java.util.List;

/** 违禁词存取端口（GM-06 在线管理的持久化侧）：按群增删改查，配合 {@code BannedWordRegistry} 热更新。 */
public interface BannedWordStore {

    /** 一条违禁词（regex 标识正则/精确词）。 */
    record Entry(String word, boolean regex) {
    }

    /** 保存/启用一条词（同词幂等）。 */
    void save(long guildId, String word, boolean regex);

    /** 停用一条词（保留记录，可再启用）。 */
    void disable(long guildId, String word);

    /** 该群当前启用的全部词。 */
    List<Entry> enabledOf(long guildId);
}
