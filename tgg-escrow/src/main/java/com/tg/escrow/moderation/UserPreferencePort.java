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

/**
 * 用户偏好端口（通知模式 all/important + ET-80 榜单公开）。榜单默认脱敏、
 * 公开默认 false——「忘配/从未表态」不改变既有匿名行为。
 */
public interface UserPreferencePort {

    /** 通知模式（all/important；未设置默认 all）。 */
    String noticeModeOf(long userId);

    /** 设置通知模式（幂等覆盖）。 */
    void setNoticeMode(long userId, String mode);

    /** 榜单可见性；未登记/从未表态恒为「不公开」。 */
    RankVisibility rankVisibilityOf(long userId);

    /** 设置榜单公开开关；username 不带前导 @，关闭时可传 null。 */
    void setRankPublic(long userId, boolean publicProfile, String username);

    /** 榜单可见性：publicProfile 是否公开、username 展示用 @username。 */
    record RankVisibility(boolean publicProfile, String username) {
    }
}
