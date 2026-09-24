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

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 管理员登记（原文档 G15）：按「群组 × 用户」记录角色，供权限判定查询。
 *
 * <h2>权限继承靠等级的比较，不靠枚举相等</h2>
 * <p>{@link #isAdmin} 用 {@link MemberRole#isAtLeast}（群主 ≥ 管理员），
 * 而不是 {@code role == ADMIN}——后者会让群主在某些判断上反而不算管理员。
 *
 * <p>本类只登记"已确认的角色"，不判"该用户是否真的是这个角色"（那要从 Telegram 取群成员信息）。
 * 按群组隔离：同一用户在不同群的角色互不影响。
 *
 * <p>非线程安全；配置写入与查询需串行（可在外层加同步）。
 */
public final class AdminRegistry {

    private final Map<Long, Map<Long, MemberRole>> byGroup = new HashMap<>();

    /**
     * 登记或更新某用户在某群的角色（幂等：重复登记覆盖旧值）。
     *
     * @param groupId 群组
     * @param userId  用户
     * @param role   角色（不得为空——不静默降级为 MEMBER）
     */
    public void add(long groupId, long userId, MemberRole role) {
        if (role == null) {
            throw new TggException("管理员登记：角色不可为空");
        }
        byGroup.computeIfAbsent(groupId, k -> new HashMap<>()).put(userId, role);
    }

    /** 移除某用户在某群的登记（幂等）。 */
    public void remove(long groupId, long userId) {
        Map<Long, MemberRole> members = byGroup.get(groupId);
        if (members != null) {
            members.remove(userId);
        }
    }

    /** 该用户在该群是否为管理员（等级 ≥ ADMIN）。 */
    public boolean isAdmin(long groupId, long userId) {
        return roleOf(groupId, userId)
                .map(role -> role.isAtLeast(MemberRole.ADMIN))
                .orElse(false);
    }

    /** 该用户在该群的角色；未登记返回空。 */
    public Optional<MemberRole> roleOf(long groupId, long userId) {
        Map<Long, MemberRole> members = byGroup.get(groupId);
        return members == null ? Optional.empty() : Optional.ofNullable(members.get(userId));
    }
}
