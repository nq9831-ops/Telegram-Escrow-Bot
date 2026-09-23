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

import java.util.Optional;

/**
 * 命令权限校验器（原文档 G22）——纯函数，不依赖 Telegram API。
 *
 * <h2>判定顺序</h2>
 * <ol>
 *   <li><b>命令是否已登记</b>：未登记 → 拒绝（fail-closed，见 {@link PermissionPolicy} 的说明）；</li>
 *   <li><b>有效角色</b>：联邦管理员视同 {@link MemberRole#OWNER}，否则用其群内角色；</li>
 *   <li><b>等级比较</b>：{@code effective.isAtLeast(required)}。</li>
 * </ol>
 *
 * <h2>为什么联邦管理员视同群主</h2>
 * <p>该白名单的设计语义就是"跨群可信的执行者"——它是全局的，与其在某个具体群里的角色无关。
 * 若把它降级为管理员，那么"联邦管理员能处置任意群"这个能力就要靠逐群加管理员来实现，
 * 既繁琐又会让权限留痕散落到每个群里。
 *
 * <h2>本类不判的事</h2>
 * <p>不判"该用户是否真的是这个角色"——那要从 Telegram 取群成员信息，属装配层的职责。
 * 本类只回答：<b>给定已确认的角色与策略，这条命令该不该执行</b>。
 */
public final class PermissionChecker {

    private PermissionChecker() {
    }

    /**
     * 判断该执行者是否可执行该命令。
     *
     * @param command 已解析的命令
     * @param actor   执行者（角色须已由上游确认）
     * @param policy  权限策略
     * @return {@code true} = 允许执行
     */
    public static boolean isAllowed(BotCommand command, CommandActor actor,
                                    PermissionPolicy policy) {
        if (command == null) {
            throw new TggException("未提供命令");
        }
        if (actor == null) {
            throw new TggException("未提供执行者");
        }
        if (policy == null) {
            throw new TggException("未提供权限策略");
        }

        Optional<MemberRole> required = policy.requiredRoleFor(command.name());
        if (required.isEmpty()) {
            // fail-closed：未登记的命令对任何人都不可执行。
            // 这样「新增命令忘了登记」会立刻暴露，而不是变成一个静默的权限漏洞。
            return false;
        }

        MemberRole effective = policy.isFederationAdmin(actor.userId())
                ? MemberRole.OWNER
                : actor.role();

        return effective.isAtLeast(required.get());
    }
}
