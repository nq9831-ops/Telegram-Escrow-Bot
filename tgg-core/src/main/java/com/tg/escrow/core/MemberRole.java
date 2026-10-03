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
package com.tg.escrow.core;

/**
 * 成员在群内的角色等级。
 *
 * <p>用带等级的枚举而非裸字符串：权限比较必须是<b>单调的</b>（群主 ≥ 管理员 ≥ 成员），
 * 而这个性质只有类型能保证。若用字符串，某处写成 {@code role == "admin"}
 * 就会让群主在某些命令上反而不如管理员——这类错误不会报错，只会表现为"偶尔不生效"。
 */
public enum MemberRole {

    /** 普通成员。 */
    MEMBER(0),

    /** 管理员。 */
    ADMIN(1),

    /** 群主。 */
    OWNER(2);

    private final int level;

    MemberRole(int level) {
        this.level = level;
    }

    /** 本角色是否达到 {@code required} 的要求（同级别视为达到）。 */
    public boolean isAtLeast(MemberRole required) {
        return this.level >= required.level;
    }
}
