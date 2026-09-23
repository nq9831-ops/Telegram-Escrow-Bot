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

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 命令权限校验的行为固定测试（原文档 G22）。
 *
 * <p>本测试最要紧的一条是<b>未登记命令一律拒绝</b>。若反过来默认放行，那么将来新增命令时
 * 忘了登记就是一个静默的安全漏洞——它不会报错、不会告警，只会让某个高危命令对所有人可用。
 * 默认拒绝会让这个疏漏立刻暴露。
 */
class PermissionCheckerTest {

    private static final long OWNER_ID = 1L;
    private static final long ADMIN_ID = 2L;
    private static final long MEMBER_ID = 3L;
    private static final long FEDERATION_ADMIN_ID = 9L;

    private static PermissionPolicy policy() {
        return new PermissionPolicy(
                Map.of(
                        "ban", MemberRole.ADMIN,
                        "warn", MemberRole.ADMIN,
                        "start", MemberRole.MEMBER,
                        "help", MemberRole.MEMBER,
                        "promote", MemberRole.OWNER),
                Set.of(FEDERATION_ADMIN_ID));
    }

    private static CommandActor actor(long userId, MemberRole role) {
        return new CommandActor(userId, role);
    }

    private static BotCommand cmd(String name) {
        return new BotCommand(name, java.util.List.of());
    }

    @Test
    @DisplayName("群主可执行任何已登记命令")
    void ownerCanDoEverything() {
        CommandActor owner = actor(OWNER_ID, MemberRole.OWNER);

        assertThat(PermissionChecker.isAllowed(cmd("start"), owner, policy())).isTrue();
        assertThat(PermissionChecker.isAllowed(cmd("ban"), owner, policy())).isTrue();
        assertThat(PermissionChecker.isAllowed(cmd("promote"), owner, policy())).isTrue();
    }

    @Test
    @DisplayName("管理员可执行 ADMIN 级与 MEMBER 级，但不能执行 OWNER 级")
    void adminCannotDoOwnerOnly() {
        CommandActor admin = actor(ADMIN_ID, MemberRole.ADMIN);

        assertThat(PermissionChecker.isAllowed(cmd("warn"), admin, policy())).isTrue();
        assertThat(PermissionChecker.isAllowed(cmd("start"), admin, policy())).isTrue();
        assertThat(PermissionChecker.isAllowed(cmd("promote"), admin, policy()))
                .as("提升权限属群主专属")
                .isFalse();
    }

    @Test
    @DisplayName("普通成员只能执行 MEMBER 级命令")
    void memberIsLimited() {
        CommandActor member = actor(MEMBER_ID, MemberRole.MEMBER);

        assertThat(PermissionChecker.isAllowed(cmd("help"), member, policy())).isTrue();
        assertThat(PermissionChecker.isAllowed(cmd("ban"), member, policy())).isFalse();
        assertThat(PermissionChecker.isAllowed(cmd("promote"), member, policy())).isFalse();
    }

    @Test
    @DisplayName("未登记的命令一律拒绝——即使执行者是群主（fail-closed）")
    void unregisteredCommandIsDeniedEvenForOwner() {
        CommandActor owner = actor(OWNER_ID, MemberRole.OWNER);

        // 这是本类最重要的一条：新增命令若忘记登记，必须立刻暴露而不是静默放行
        assertThat(PermissionChecker.isAllowed(cmd("unknown_cmd"), owner, policy()))
                .as("未登记命令对任何人都不可执行")
                .isFalse();
        // 用「名字合法但未登记」的命令——空名会在 BotCommand 构造期就被拒，
        // 那样测到的不是权限逻辑
        assertThat(PermissionChecker.isAllowed(cmd("promote_all"), owner, policy())).isFalse();
    }

    @Test
    @DisplayName("联邦管理员凭白名单获得群主级权限——即使其群内角色只是普通成员")
    void federationAdminGetsOwnerLevel() {
        CommandActor foreign = actor(FEDERATION_ADMIN_ID, MemberRole.MEMBER);

        assertThat(PermissionChecker.isAllowed(cmd("promote"), foreign, policy()))
                .as("联邦管理员是全局白名单，与其在某个群里的角色无关")
                .isTrue();
        assertThat(PermissionChecker.isAllowed(cmd("ban"), foreign, policy())).isTrue();
    }

    @Test
    @DisplayName("白名单外的人即便自称管理员也不越权（白名单只认 ID）")
    void nonWhitelistedAdminStaysAtOwnLevel() {
        CommandActor admin = actor(ADMIN_ID, MemberRole.ADMIN);

        assertThat(PermissionChecker.isAllowed(cmd("promote"), admin, policy())).isFalse();
    }

    @Test
    @DisplayName("命令名大小写不敏感——与 CommandParser 的归一化一致")
    void commandNameIsCaseInsensitive() {
        CommandActor owner = actor(OWNER_ID, MemberRole.OWNER);

        assertThat(PermissionChecker.isAllowed(cmd("BAN"), owner, policy())).isTrue();
        assertThat(PermissionChecker.isAllowed(cmd("  ban  "), owner, policy())).isTrue();
    }

    @Test
    @DisplayName("角色为 null → 抛异常（不做「未知角色即放行」的推断）")
    void nullRoleIsRejected() {
        assertThatThrownBy(() -> PermissionChecker.isAllowed(
                cmd("start"), new CommandActor(MEMBER_ID, null), policy()))
                .isInstanceOf(TggException.class);
    }

    @Test
    @DisplayName("命令或执行者为 null → 抛异常")
    void nullArgumentsAreRejected() {
        CommandActor owner = actor(OWNER_ID, MemberRole.OWNER);

        assertThatThrownBy(() -> PermissionChecker.isAllowed(null, owner, policy()))
                .isInstanceOf(TggException.class);
        assertThatThrownBy(() -> PermissionChecker.isAllowed(cmd("start"), null, policy()))
                .isInstanceOf(TggException.class);
        assertThatThrownBy(() -> PermissionChecker.isAllowed(cmd("start"), owner, null))
                .isInstanceOf(TggException.class);
    }

    @Test
    @DisplayName("策略缺少必填项 → 构造期抛异常")
    void invalidPolicyFailsFast() {
        assertThatThrownBy(() -> new PermissionPolicy(null, Set.of()))
                .isInstanceOf(TggException.class);

        // 刻意用可变 Map：Map.of 自身就拒绝 null 值，会在进入构造器之前抛 NPE，
        // 那样这条断言测到的就不是本类的校验逻辑了
        Map<String, MemberRole> withNullRole = new java.util.HashMap<>();
        withNullRole.put("x", null);
        assertThatThrownBy(() -> new PermissionPolicy(withNullRole, Set.of()))
                .isInstanceOf(TggException.class);

        Map<String, MemberRole> withBlankName = new java.util.HashMap<>();
        withBlankName.put("   ", MemberRole.ADMIN);
        assertThatThrownBy(() -> new PermissionPolicy(withBlankName, Set.of()))
                .isInstanceOf(TggException.class);
    }

    @Test
    @DisplayName("联邦管理员白名单为 null → 视为空（而不是「所有人都是」）")
    void nullFederationAdminsMeansNone() {
        PermissionPolicy noWhitelist = new PermissionPolicy(
                Map.of("promote", MemberRole.OWNER), null);

        assertThat(PermissionChecker.isAllowed(
                cmd("promote"), actor(FEDERATION_ADMIN_ID, MemberRole.MEMBER), noWhitelist))
                .as("白名单缺失不等于全员特权")
                .isFalse();
    }

    @Test
    @DisplayName("角色等级比较是单调的")
    void roleLevelsAreOrdered() {
        assertThat(MemberRole.OWNER.isAtLeast(MemberRole.ADMIN)).isTrue();
        assertThat(MemberRole.ADMIN.isAtLeast(MemberRole.ADMIN)).isTrue();
        assertThat(MemberRole.MEMBER.isAtLeast(MemberRole.ADMIN)).isFalse();
        assertThat(MemberRole.ADMIN.isAtLeast(MemberRole.OWNER)).isFalse();
    }
}
