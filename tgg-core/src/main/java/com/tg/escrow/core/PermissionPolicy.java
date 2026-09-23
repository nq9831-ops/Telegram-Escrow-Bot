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

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 命令权限策略——<b>命令与所需角色的映射，以及联邦管理员白名单</b>。
 *
 * <p>与 {@code RiskPolicy} 同样的思路：代码实现机制，判断（哪个命令该由谁执行）留在配置里。
 *
 * <h2>未登记命令的处理</h2>
 * <p>{@link PermissionChecker} 对<b>未出现在 {@code requiredRoles} 里的命令一律拒绝</b>。
 * 这是刻意的 fail-closed：若默认放行，那么将来新增命令时忘了登记就是一个静默的安全漏洞
 * ——不报错、不告警，只是某个高危命令突然对所有人可用。默认拒绝会让这个疏漏<b>立刻暴露</b>。
 *
 * @param requiredRoles   命令名（小写）到最低所需角色的映射；<b>未登记的命令不可执行</b>
 * @param federationAdmins 联邦管理员 ID 白名单（全局，与群内角色无关）；
 *                         {@code null} 视为空集——<b>绝不意味着"所有人都是"</b>
 */
public record PermissionPolicy(Map<String, MemberRole> requiredRoles,
                               Set<Long> federationAdmins) {

    public PermissionPolicy {
        if (requiredRoles == null) {
            throw new TggException("权限策略未提供命令角色映射");
        }
        for (Map.Entry<String, MemberRole> entry : requiredRoles.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                throw new TggException("权限策略含空的命令名");
            }
            if (entry.getValue() == null) {
                throw new TggException("命令「" + entry.getKey() + "」未指定所需角色");
            }
        }
        requiredRoles = Map.copyOf(requiredRoles);
        federationAdmins = federationAdmins == null ? Set.of() : Set.copyOf(federationAdmins);
    }

    /**
     * 查某命令所需的最低角色。
     *
     * <p>命令名在此处归一化（去空白 + 转小写），与 {@link CommandParser} 的处理保持一致——
     * 否则 {@code /BAN} 能解析成命令却查不到权限要求，结果是被拒绝，用户会觉得"命令没反应"。
     */
    public Optional<MemberRole> requiredRoleFor(String commandName) {
        if (commandName == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(requiredRoles.get(commandName.trim().toLowerCase(Locale.ROOT)));
    }

    /** 是否联邦管理员。 */
    public boolean isFederationAdmin(long userId) {
        return federationAdmins.contains(userId);
    }
}
