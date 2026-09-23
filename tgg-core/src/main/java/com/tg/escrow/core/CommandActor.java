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

import com.tg.escrow.common.TggException;

/**
 * 命令执行者。
 *
 * <p>只带两个字段：用户 ID 与其<b>在当前群</b>内的角色。
 * 刻意不带"是否联邦管理员"——那是全局属性，由 {@link PermissionPolicy} 的白名单判定。
 * 放进来会让人误以为角色与白名单是同一层的东西，而实际上
 * <b>白名单是全局的、角色是群内的</b>，两者维度不同。
 *
 * @param userId 用户在 Telegram 的 ID
 * @param role   该用户在本群内的角色
 */
public record CommandActor(long userId, MemberRole role) {

    public CommandActor {
        if (role == null) {
            // 不做「未知角色按最低权限处理」的推断：角色拿不到意味着上游取数据出错，
            // 静默降级会让人以为命令"没反应"而不是"出错了"。
            throw new TggException("命令执行者的角色未提供（userId=" + userId + "）");
        }
    }
}
