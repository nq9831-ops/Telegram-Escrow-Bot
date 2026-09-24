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
import com.tg.escrow.core.WarningPolicy.Action;

import java.time.Duration;

/**
 * 警告处置编排（GM-03 接线）：累计警告 → {@link WarningPolicy} 判罚 → {@link ModerationOrchestrator} 执行。
 *
 * <p>把三个零件串成一条链：<b>判罚为 NONE 时零处置调用</b>（不预罚）；MUTE 用默认时长执行禁言；
 * KICK 执行踢出。处置仍过 {@code ModerationOrchestrator} 的<b>单调守卫</b>——
 * 编排链不绕过权限（管理员的警告处置同样不能动同级）。
 */
public final class WarningOrchestrator {

    private final WarningPolicy policy;
    private final ModerationOrchestrator moderation;
    private final Duration defaultMuteDuration;

    /**
     * @param policy             警告判罚阈值
     * @param moderation         处置编排（含单调守卫）
     * @param defaultMuteDuration 判罚 MUTE 时的默认禁言时长（正数）
     */
    public WarningOrchestrator(WarningPolicy policy, ModerationOrchestrator moderation,
                               Duration defaultMuteDuration) {
        if (policy == null || moderation == null) {
            throw new TggException("警告处置：判罚策略与处置编排均不可为空");
        }
        if (defaultMuteDuration == null || defaultMuteDuration.isZero()
                || defaultMuteDuration.isNegative()) {
            throw new TggException("警告处置：默认禁言时长必须为正，实为 " + defaultMuteDuration);
        }
        this.policy = policy;
        this.moderation = moderation;
        this.defaultMuteDuration = defaultMuteDuration;
    }

    /**
     * 记录警告并按阈值处置。
     *
     * @param guildId        群
     * @param actor          处置执行者（仍受单调守卫约束）
     * @param targetId       被警告用户
     * @param targetRole     被警告用户角色
     * @param currentWarnings 当前累计警告数（调用方从 {@code WarningPort} 取）
     * @return 判罚动作
     */
    public Action handle(long guildId, CommandActor actor, long targetId,
                         MemberRole targetRole, int currentWarnings) {
        Action action = policy.actionFor(currentWarnings);
        switch (action) {
            case MUTE -> moderation.mute(guildId, actor, targetId, targetRole, defaultMuteDuration);
            case KICK -> moderation.kick(guildId, actor, targetId, targetRole);
            case NONE -> { /* 不预罚 */ }
        }
        return action;
    }
}
