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

/**
 * 交易历史查询端口——准入事实快照。契约：返回的必须是<b>该 subjectId</b> 的历史
 * （传错人=资金级错误）；查不清<b>抛异常</b>而非返回空历史（空历史会被门禁解读为「无限制」，
 * 等于把查询故障变成放行）。
 */
@FunctionalInterface
public interface TradeHistoryPort {

    /** 汇总该发起人的准入事实（进行中笔数/最近完成时刻/有无未决争议）。 */
    TradeAdmissionContext snapshotOf(long subjectId);
}
