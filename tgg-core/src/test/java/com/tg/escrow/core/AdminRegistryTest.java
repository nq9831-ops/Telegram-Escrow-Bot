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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 管理员登记（G15）的行为固定测试。
 *
 * <p>权限比较复用 {@link MemberRole#isAtLeast}：群主天然满足管理员要求（等级单调），
 * 避免"群主反而不如管理员"这类只在特定命令上暴露的错位。
 */
class AdminRegistryTest {

    @Test
    @DisplayName("未添加 → 非管理员，且角色为空")
    void unknownUserIsNotAdmin() {
        AdminRegistry r = new AdminRegistry();

        assertThat(r.isAdmin(1L, 100L)).isFalse();
        assertThat(r.roleOf(1L, 100L)).isEmpty();
    }

    @Test
    @DisplayName("添加 ADMIN → 是管理员")
    void addedAdminIsAdmin() {
        AdminRegistry r = new AdminRegistry();

        r.add(1L, 100L, MemberRole.ADMIN);

        assertThat(r.isAdmin(1L, 100L)).isTrue();
        assertThat(r.roleOf(1L, 100L)).hasValue(MemberRole.ADMIN);
    }

    @Test
    @DisplayName("添加 OWNER → 是管理员（等级继承：群主 ≥ 管理员）")
    void ownerIsAdminByInheritance() {
        AdminRegistry r = new AdminRegistry();

        r.add(1L, 100L, MemberRole.OWNER);

        assertThat(r.isAdmin(1L, 100L)).isTrue();
    }

    @Test
    @DisplayName("登记为 MEMBER → 不是管理员，但角色可查")
    void memberIsNotAdmin() {
        AdminRegistry r = new AdminRegistry();

        r.add(1L, 100L, MemberRole.MEMBER);

        assertThat(r.isAdmin(1L, 100L)).isFalse();
        assertThat(r.roleOf(1L, 100L)).hasValue(MemberRole.MEMBER);
    }

    @Test
    @DisplayName("移除后不再是管理员")
    void removedIsNotAdmin() {
        AdminRegistry r = new AdminRegistry();
        r.add(1L, 100L, MemberRole.ADMIN);

        r.remove(1L, 100L);
        assertThat(r.isAdmin(1L, 100L)).isFalse();
    }

    @Test
    @DisplayName("按群组隔离：同一用户在不同群的角色互不影响")
    void isolatedByGroup() {
        AdminRegistry r = new AdminRegistry();
        r.add(1L, 100L, MemberRole.ADMIN);

        assertThat(r.isAdmin(1L, 100L)).isTrue();
        assertThat(r.isAdmin(2L, 100L)).isFalse();
    }

    @Test
    @DisplayName("重复 add 覆盖旧角色（幂等更新）")
    void addIsIdempotentUpdate() {
        AdminRegistry r = new AdminRegistry();
        r.add(1L, 100L, MemberRole.ADMIN);

        r.add(1L, 100L, MemberRole.MEMBER);
        assertThat(r.roleOf(1L, 100L)).hasValue(MemberRole.MEMBER);
    }

    @Test
    @DisplayName("null 角色 → 拒绝（不静默降级为 MEMBER）")
    void nullRoleRejected() {
        AdminRegistry r = new AdminRegistry();

        assertThatThrownBy(() -> r.add(1L, 100L, null)).isInstanceOf(TggException.class);
    }
}
