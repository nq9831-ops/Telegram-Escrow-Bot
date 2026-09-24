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

import java.time.Duration;

/**
 * 群管理处置编排（GM-01/02/08）：守卫先于执行。
 *
 * <h2>单调守卫：只能处置角色低于自己的目标</h2>
 * <p>管理员不得处置管理员/群主、群主不得处置群主——防越权与防内斗。这是权限系统（GM-31）
 * 的等级单调性在处置动作上的落点：<b>普通成员零处置权</b>。
 *
 * <p>守卫失败抛异常且<b>端口零调用</b>（测试钉住）——处置动作有外部副作用，
 * 顺序必须"先判后动"。编排本身无状态。
 */
public final class ModerationOrchestrator {

    /**
     * 联邦旁路的审计记录（旁路动作必须留痕——单调守卫的例外只能走可审计的显式通道）。
     *
     * @param action   动作名（kick / ban）
     * @param actorId  执行者（联邦管理员）
     * @param targetId 处置对象
     */
    public record Audit(String action, long actorId, long targetId) {
    }

    private final GroupAdminPort port;
    private final PermissionPolicy federationPolicy;

    /** 无联邦旁路的编排（federationXxx 一律拒绝——fail-closed）。 */
    public ModerationOrchestrator(GroupAdminPort port) {
        this(port, null);
    }

    /**
     * @param port             管理动作端口
     * @param federationPolicy 联邦白名单（配置了才启用联邦旁路；{@code null} = 旁路关闭）
     */
    public ModerationOrchestrator(GroupAdminPort port, PermissionPolicy federationPolicy) {
        if (port == null) {
            throw new TggException("处置编排：管理动作端口未提供");
        }
        this.port = port;
        this.federationPolicy = federationPolicy;
    }

    /** 踢出（GM-01）。 */
    public void kick(long guildId, CommandActor actor, long targetId, MemberRole targetRole) {
        requireModerator(actor, targetRole, "踢出");
        port.kick(guildId, targetId);
    }

    /** 封禁（GM-01）。 */
    public void ban(long guildId, CommandActor actor, long targetId, MemberRole targetRole) {
        requireModerator(actor, targetRole, "封禁");
        port.ban(guildId, targetId);
    }

    /** 定时禁言（GM-02）。 */
    public void mute(long guildId, CommandActor actor, long targetId, MemberRole targetRole,
                     Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new TggException("处置编排：禁言时长必须为正，实为 " + duration);
        }
        requireModerator(actor, targetRole, "禁言");
        port.mute(guildId, targetId, duration);
    }

    /** 删除消息（GM-08）：管理员及以上均可删（消息无角色归属）。 */
    public void deleteMessage(long guildId, CommandActor actor, long messageId) {
        if (actor == null) {
            throw new TggException("处置编排：执行者未提供");
        }
        if (!actor.role().isAtLeast(MemberRole.ADMIN)) {
            throw new TggException("处置编排：普通成员无删除消息权限");
        }
        port.deleteMessage(guildId, messageId);
    }

    private static void requireModerator(CommandActor actor, MemberRole targetRole, String action) {
        if (actor == null || targetRole == null) {
            throw new TggException("处置编排：" + action + " 参数不完整");
        }
        if (!actor.role().isAtLeast(MemberRole.ADMIN)) {
            throw new TggException("处置编排：普通成员无" + action + "权限");
        }
        if (!actor.role().isAtLeast(targetRole) || actor.role() == targetRole) {
            // 单调守卫：只可处置严格低于自己的角色
            throw new TggException("处置编排：只能" + action + "角色低于自己的成员（执行者 "
                    + actor.role() + "，目标 " + targetRole + "）");
        }
    }

    /**
     * 联邦旁路踢出（GM-04/T4 仲裁处置）：<b>联邦管理员可处置任意角色</b>（含群主），
     * 不受单调守卫限制——但必须走本显式通道并<b>返回审计记录</b>（调用方负责持久化/推送留痕）。
     *
     * <p>与单调守卫的关系：旁路是"跨群特权"而非"同级互殴"——普通管理路径的守卫一行未动。
     * 非联邦管理员调用一律拒绝；未配置联邦策略时旁路 fail-closed。
     */
    public Audit federationKick(long guildId, CommandActor actor, long targetId, MemberRole targetRole) {
        return federationAct("kick", guildId, actor, targetId, targetRole);
    }

    /** 联邦旁路封禁（同 {@link #federationKick} 语义）。 */
    public Audit federationBan(long guildId, CommandActor actor, long targetId, MemberRole targetRole) {
        return federationAct("ban", guildId, actor, targetId, targetRole);
    }

    private Audit federationAct(String action, long guildId, CommandActor actor,
                                long targetId, MemberRole targetRole) {
        if (actor == null || targetRole == null) {
            throw new TggException("处置编排：联邦" + action + " 参数不完整");
        }
        if (federationPolicy == null) {
            throw new TggException("处置编排：联邦旁路未配置——拒绝执行（fail-closed）");
        }
        if (!federationPolicy.isFederationAdmin(actor.userId())) {
            throw new TggException("处置编排：仅联邦管理员可走旁路" + action);
        }
        if ("kick".equals(action)) {
            port.kick(guildId, targetId);
        } else {
            port.ban(guildId, targetId);
        }
        return new Audit(action, actor.userId(), targetId);
    }
}
