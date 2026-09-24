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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 群管理编排（GM-01/02/08）的行为固定测试。
 *
 * <p>核心守卫：<b>只能处置角色低于自己的目标</b>——管理员不能踢管理员/群主、群主不能处置群主。
 * 这条防内斗/防越权的单调规则是权限系统（GM-31）在处置动作上的落点。
 * 端口 fake 记录调用，验证"守卫先于执行"。
 */
class ModerationOrchestratorTest {

    /** fake 端口：记录调用，不真连 Telegram（真实实现属线上清单 A 组）。 */
    private static final class FakeAdminPort implements GroupAdminPort {
        final List<String> calls = new ArrayList<>();

        @Override
        public void kick(long guildId, long userId) {
            calls.add("kick:" + userId);
        }

        @Override
        public void ban(long guildId, long userId) {
            calls.add("ban:" + userId);
        }

        @Override
        public void mute(long guildId, long userId, Duration duration) {
            calls.add("mute:" + userId);
        }

        @Override
        public void deleteMessage(long guildId, long messageId) {
            calls.add("del:" + messageId);
        }
    }

    private final FakeAdminPort port = new FakeAdminPort();
    private final ModerationOrchestrator orch = new ModerationOrchestrator(port);

    @Test
    @DisplayName("管理员可踢普通成员 → 端口被调用")
    void adminKicksMember() {
        orch.kick(1L, new CommandActor(10L, MemberRole.ADMIN), 20L, MemberRole.MEMBER);

        assertThat(port.calls).containsExactly("kick:20");
    }

    @Test
    @DisplayName("管理员不得处置同级/更高级——ADMIN 踢 ADMIN、ADMIN 踢 OWNER 均拒绝且不动端口")
    void cannotModerateEqualOrHigher() {
        assertThatThrownBy(() -> orch.kick(1L, new CommandActor(10L, MemberRole.ADMIN), 21L, MemberRole.ADMIN))
                .isInstanceOf(TggException.class);
        assertThatThrownBy(() -> orch.kick(1L, new CommandActor(10L, MemberRole.ADMIN), 22L, MemberRole.OWNER))
                .isInstanceOf(TggException.class);

        assertThat(port.calls).isEmpty(); // 守卫先于执行，端口零调用
    }

    @Test
    @DisplayName("普通成员无处置权（MEMBER 踢 MEMBER 拒绝）")
    void memberCannotModerate() {
        assertThatThrownBy(() -> orch.kick(1L, new CommandActor(10L, MemberRole.MEMBER), 20L, MemberRole.MEMBER))
                .isInstanceOf(TggException.class);
    }

    @Test
    @DisplayName("禁言同受单调守卫；时长必须为正")
    void muteGuarded() {
        orch.mute(1L, new CommandActor(10L, MemberRole.OWNER), 20L, MemberRole.MEMBER,
                Duration.ofMinutes(10));
        assertThat(port.calls).containsExactly("mute:20");

        assertThatThrownBy(() -> orch.mute(1L, new CommandActor(10L, MemberRole.ADMIN), 20L,
                MemberRole.MEMBER, Duration.ZERO)).isInstanceOf(TggException.class);
    }

    @Test
    @DisplayName("删除消息：管理员可删（GM-08 违规消息处置）")
    void adminDeletesMessage() {
        orch.deleteMessage(1L, new CommandActor(10L, MemberRole.ADMIN), 999L);

        assertThat(port.calls).containsExactly("del:999");
    }

    // ---- 联邦旁路（GM-04/T4 仲裁处置）：显式通道 + 强制审计，不放宽单调守卫 ----

    private static PermissionPolicy federationPolicy(long federationId) {
        return new PermissionPolicy(Map.of(), Set.of(federationId));
    }

    @Test
    @DisplayName("联邦管理员可处置任意角色（含 OWNER）——旁路单调守卫，且返回审计记录")
    void federationCanModerateAnyone() {
        ModerationOrchestrator fed = new ModerationOrchestrator(port, federationPolicy(10L));

        ModerationOrchestrator.Audit audit =
                fed.federationKick(1L, new CommandActor(10L, MemberRole.MEMBER), 22L, MemberRole.OWNER);

        assertThat(port.calls).containsExactly("kick:22");
        assertThat(audit.action()).isEqualTo("kick");
        assertThat(audit.actorId()).isEqualTo(10L);
        assertThat(audit.targetId()).isEqualTo(22L);
    }

    @Test
    @DisplayName("非联邦管理员不得走旁路（普通 ADMIN 调 federationKick 拒绝）")
    void nonFederationRejected() {
        ModerationOrchestrator fed = new ModerationOrchestrator(port, federationPolicy(10L));

        assertThatThrownBy(() -> fed.federationKick(1L,
                new CommandActor(11L, MemberRole.ADMIN), 20L, MemberRole.MEMBER))
                .isInstanceOf(TggException.class);
        assertThat(port.calls).isEmpty();
    }

    @Test
    @DisplayName("未配置联邦策略 → 旁路 fail-closed 拒绝（不可静默放行）")
    void federationBypassWithoutPolicyRejected() {
        assertThatThrownBy(() -> orch.federationKick(1L,
                new CommandActor(10L, MemberRole.OWNER), 20L, MemberRole.MEMBER))
                .isInstanceOf(TggException.class);
        assertThat(port.calls).isEmpty();
    }
}
