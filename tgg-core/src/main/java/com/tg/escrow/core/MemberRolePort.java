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

/**
 * 群内角色查询端口（G-C 前置）。真实实现在装配层对接 Telegram {@code getChatMember}。
 *
 * <h2>为什么单独一个端口</h2>
 * <p>{@link ModerationOrchestrator} 的处置守卫完全建立在「执行者的群内角色」之上。角色若只能
 * 靠直连 Telegram 取，守卫就无法在纯本地被穷举测试——故把查询抽成端口，让编排层可测。
 *
 * <h2>实现契约：fail-closed</h2>
 * <p>查不到、状态未知、或调用失败时<b>必须返回最低权限</b>（{@link MemberRole#MEMBER}），
 * <b>绝不能</b>因"没看懂"而给出 ADMIN/OWNER——那等于把一次查询故障变成越权通道。
 * 权限只能因证据收紧，不能因故障放松。
 */
@FunctionalInterface
public interface MemberRolePort {

    /**
     * 查某用户在群内的角色。
     *
     * @param chatId 群 ID
     * @param userId 用户 ID
     * @return 该用户的群内角色；任何不确定情形返回 {@link MemberRole#MEMBER}
     */
    MemberRole roleOf(long chatId, long userId);
}
