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
 * 聊天类型（Wave 1）——内容安全是否适用于该会话。
 *
 * <h2>为什么必须区分</h2>
 * <p>内容安全处置（删消息 + 记警告）是<b>群治理</b>手段。此前全仓没有任何聊天类型判断，
 * 于是私聊里发一条带链接的消息也会触发删消息——那既不是治理目的，也是对用户的敌意行为
 * （且若删除失败，用户还会收到一条莫名其妙的"处置失败"回执）。
 *
 * <h2>判据放在枚举上</h2>
 * <p>{@link #moderatable()} 把"哪些会话类型适用治理"这一事实与枚举本身绑在一起，
 * 调用方不必各写一遍 switch——散落多处的判定必然漂移。
 */
public enum ChatKind {

    /** 与 bot 的私聊。 */
    PRIVATE,
    /** 普通群。 */
    GROUP,
    /** 超级群。 */
    SUPERGROUP,
    /** 频道，或无法识别的会话类型。 */
    CHANNEL;

    /**
     * 内容安全处置（删消息/累计警告）是否适用于该类型。
     *
     * <p>仅群与超级群为真。频道与私聊<b>不适用</b>——前者 bot 不是以管理员身份管理内容，
     * 后者根本没有"群"这一治理场景。未知类型同样取否：无法确认是群，就不执行删消息这类
     * 破坏性动作。
     */
    public boolean moderatable() {
        return this == GROUP || this == SUPERGROUP;
    }
}
