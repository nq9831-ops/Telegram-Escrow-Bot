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
 * 入群风险因子。
 *
 * <p>这些是<b>信号</b>，不是结论：每一个单独看都可能是良民（新账号可能只是刚用 Telegram，
 * 无头像可能只是注重隐私）。评分机制把它们累加成一个概率判断，供 REVIEW/REJECT 使用。
 *
 * <p>因子集合刻意保持小而可解释。加入"消息内容""设备指纹"这类更强但更侵入的信号前，
 * 需要先想清楚它的误伤面与合规含义。
 */
public enum RiskFactor {

    /** 无头像。 */
    NO_AVATAR,

    /** 无用户名。 */
    NO_USERNAME,

    /** 账号年龄低于配置门槛。 */
    NEW_ACCOUNT,

    /**
     * 语言不在允许列表内。
     *
     * <p><b>命名刻意用"不在允许列表"而非"可疑语言"</b>：哪些语言被列入、
     * 是否该做这个限制，是业务与合规决策，不是代码该固化的判断。
     * 在代码里写死"某语言可疑"，等于把歧视做成实现，且无法按辖区调整。
     */
    LANGUAGE_NOT_ALLOWED
}
