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
package com.tg.escrow;

import com.tg.escrow.common.TggException;
import com.tg.escrow.core.BotCommand;
import com.tg.escrow.core.CommandActor;
import com.tg.escrow.core.MemberRole;
import com.tg.escrow.core.MemberRolePort;
import com.tg.escrow.core.ModerationOrchestrator;
import com.tg.escrow.core.WarningOrchestrator;
import com.tg.escrow.core.WarningPolicy;
import com.tg.escrow.moderation.WarningPort;

import java.time.Duration;

/**
 * 群管理命令处理器（Wave 3 接线）——把 {@code /kick /ban /mute /del} 接到编排层。
 *
 * <h2>职责边界</h2>
 * <p>本类<b>只做</b>：解析参数、取目标角色、转述结果。权限与单调守卫全在
 * {@link ModerationOrchestrator}——不在这里另写一套判定，免得两处规则漂移。
 *
 * <h2>为什么要取「目标的角色」</h2>
 * <p>编排层的单调守卫要求执行者<b>高于</b>被处置者：管理员不能踢同级管理员或群主。
 * 该判断需要目标的真实角色，故本类依赖 {@link MemberRolePort} 取之；取不到时端口
 * 一律返回最低角色（fail-closed），于是守卫会拒绝——方向正确。
 */
public final class ModerationCommandHandler {

    /** 用法说明（公开，供测试与分发器复用）。 */
    public static final String USAGE = "用法：/kick <用户ID> 踢出；/ban <用户ID> 封禁；"
            + "/mute <用户ID> <分钟> 禁言；/del <消息ID> 删除消息；"
            + "/warn <用户ID> 警告（累计达阈值自动处置）；/unwarn <用户ID> 清除警告（均需管理员权限）";

    private static final String CMD_KICK = "kick";
    private static final String CMD_BAN = "ban";
    private static final String CMD_MUTE = "mute";
    private static final String CMD_DEL = "del";
    private static final String CMD_WARN = "warn";
    private static final String CMD_UNWARN = "unwarn";

    private final ModerationOrchestrator moderation;
    private final MemberRolePort roles;
    private final WarningPort warnings;
    private final WarningOrchestrator warningOrchestrator;

    public ModerationCommandHandler(ModerationOrchestrator moderation, MemberRolePort roles,
                                    WarningPort warnings, WarningOrchestrator warningOrchestrator) {
        if (moderation == null) {
            throw new TggException("群管理命令：处置编排不可为空");
        }
        if (roles == null) {
            throw new TggException("群管理命令：角色查询端口不可为空");
        }
        if (warnings == null) {
            throw new TggException("群管理命令：警告端口不可为空");
        }
        if (warningOrchestrator == null) {
            throw new TggException("群管理命令：警告处置编排不可为空");
        }
        this.moderation = moderation;
        this.roles = roles;
        this.warnings = warnings;
        this.warningOrchestrator = warningOrchestrator;
    }

    /** 本处理器是否管辖该命令。 */
    public boolean canHandle(BotCommand cmd) {
        if (cmd == null) {
            return false;
        }
        String name = cmd.name();
        return CMD_KICK.equals(name) || CMD_BAN.equals(name)
                || CMD_MUTE.equals(name) || CMD_DEL.equals(name)
                || CMD_WARN.equals(name) || CMD_UNWARN.equals(name);
    }

    /**
     * 处理一条群管理命令。
     *
     * @param cmd    命令（须由 {@link #canHandle} 判定为管辖）
     * @param actor  执行者（角色取自群内真实状态）
     * @param chatId 群 ID
     * @return 用户可读回执；参数缺失/非法时返回 {@link #USAGE}
     */
    public String handle(BotCommand cmd, CommandActor actor, long chatId) {
        if (cmd == null || actor == null) {
            throw new TggException("群管理命令：命令与执行者不可为空");
        }
        if (!canHandle(cmd)) {
            throw new TggException("群管理命令：不处理的命令 " + cmd.name());
        }
        String name = cmd.name();

        if (CMD_WARN.equals(name)) {
            return handleWarn(cmd, actor, chatId);
        }
        if (CMD_UNWARN.equals(name)) {
            return handleUnwarn(cmd, actor, chatId);
        }

        if (CMD_DEL.equals(name)) {
            Long messageId = parseLong(cmd.argOpt(0).orElse(null));
            if (messageId == null) {
                return USAGE;
            }
            try {
                moderation.deleteMessage(chatId, actor, messageId);
            } catch (TggException ex) {
                return "无法删除消息：" + ex.getMessage();
            }
            return "已删除消息 #" + messageId;
        }

        Long targetId = parseLong(cmd.argOpt(0).orElse(null));
        if (targetId == null) {
            return USAGE;
        }
        MemberRole targetRole = roles.roleOf(chatId, targetId);

        try {
            switch (name) {
                case CMD_KICK -> {
                    moderation.kick(chatId, actor, targetId, targetRole);
                    return "已踢出 " + targetId;
                }
                case CMD_BAN -> {
                    moderation.ban(chatId, actor, targetId, targetRole);
                    return "已封禁 " + targetId;
                }
                case CMD_MUTE -> {
                    Long minutes = parseLong(cmd.argOpt(1).orElse(null));
                    if (minutes == null || minutes <= 0) {
                        return USAGE;
                    }
                    moderation.mute(chatId, actor, targetId, targetRole, Duration.ofMinutes(minutes));
                    return "已禁言 " + targetId + "（" + minutes + " 分钟）";
                }
                default -> {
                    return USAGE;
                }
            }
        } catch (TggException ex) {
            // 编排层的守卫拒绝（无权 / 目标角色不低于自己）在这里转成用户能懂的话
            return "无法" + actionText(name) + "：" + ex.getMessage();
        }
    }

    private static String actionText(String name) {
        return switch (name) {
            case CMD_KICK -> "踢出";
            case CMD_BAN -> "封禁";
            case CMD_MUTE -> "禁言";
            default -> "操作";
        };
    }

    /**
     * 警告：<b>先鉴权、再记录、后判罚</b>。
     *
     * <p>顺序不可颠倒——先记录再鉴权的话，普通成员就能把别人的警告数刷到判罚线。
     * 编排层入口也有同样的守卫（双保险：本层拦在副作用之前，编排层拦在所有调用方之前）。
     */
    private String handleWarn(BotCommand cmd, CommandActor actor, long chatId) {
        Long targetId = parseLong(cmd.argOpt(0).orElse(null));
        if (targetId == null) {
            return USAGE;
        }
        if (!isAdmin(actor)) {
            return "无权警告：需要管理员权限";
        }
        MemberRole targetRole = roles.roleOf(chatId, targetId);
        int count = warnings.warn(chatId, targetId);
        try {
            WarningPolicy.Action action =
                    warningOrchestrator.handle(chatId, actor, targetId, targetRole, count);
            return "已警告 " + targetId + "（累计 " + count + " 次）→ 处置：" + penaltyText(action);
        } catch (TggException ex) {
            // 警告已记、处罚没执行——两件都说，别让管理员以为人已被处置
            return "已警告 " + targetId + "（累计 " + count + " 次），但处罚未执行：" + ex.getMessage();
        }
    }

    private String handleUnwarn(BotCommand cmd, CommandActor actor, long chatId) {
        Long targetId = parseLong(cmd.argOpt(0).orElse(null));
        if (targetId == null) {
            return USAGE;
        }
        if (!isAdmin(actor)) {
            return "无权清除警告：需要管理员权限";
        }
        warnings.clear(chatId, targetId);
        return "已清除 " + targetId + " 的警告记录";
    }

    private static boolean isAdmin(CommandActor actor) {
        return actor != null && actor.role() != null && actor.role().isAtLeast(MemberRole.ADMIN);
    }

    private static String penaltyText(WarningPolicy.Action action) {
        return switch (action) {
            case NONE -> "仅记录（未达阈值）";
            case MUTE -> "已禁言";
            case KICK -> "已踢出";
        };
    }

    private static Long parseLong(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
