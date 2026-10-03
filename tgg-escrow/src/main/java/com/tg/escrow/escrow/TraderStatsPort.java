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
package com.tg.escrow.escrow;

import java.util.List;

/**
 * 交易者统计读取端口——信用/榜单的数据接缝。评分在 Java 侧由 {@link TraderCreditService}
 * 计算（库中无分数列），故本端口只供「参与者 + 统计」，排序/截断由上层内存完成。
 * 已知规模边界：{@link #allWithActivity()} 行数 = 有成交史用户数；量级上涨时物化榜单表，不推 SQL 排序。
 */
public interface TraderStatsPort {

    /** 取某用户统计；无成交史返回<b>空统计</b>（计数为 0），不返回 null、不抛——新人是正常状态。 */
    TraderStats of(long userId);

    /** 取所有有成交史用户的统计；无成交史返回空列表。顺序不保证（排序是上层职责）。 */
    List<TraderStats> allWithActivity();
}
