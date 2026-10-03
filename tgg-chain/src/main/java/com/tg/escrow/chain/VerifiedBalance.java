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
package com.tg.escrow.chain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;

/**
 * 经交叉验证的余额结论——<b>携带证据，而非裸布尔</b>。
 *
 * <p>这是本模块最核心的设计决定。若读取接口返回 {@code boolean funded}，那么调用方
 * （乃至事后审计的人）永远无法回答："这是谁说的？几个源确认过？确认深度多少？"
 * 而资金事故的复盘恰恰依赖这些。
 *
 * <p>携带证据还有一层作用：它让"信任等级"成为<b>数据</b>而非<b>约定</b>。
 * 一个由 3 个独立源、30 个确认支撑的结论，与只有 2 个同厂商源、12 个确认支撑的结论，
 * 可以在业务层被区别对待——而裸布尔做不到这件事。
 *
 * @param balance          多源一致的余额
 * @param sources          作出确认的来源标识集合（至少两个）
 * @param minConfirmations 各源确认数中的<b>最小值</b>（最悲观者），而非平均或最大
 * @param observedAt       各源观测时刻中的<b>最晚</b>者——表示"截至此时，该结论仍成立"
 */
public record VerifiedBalance(BigDecimal balance, Set<String> sources, int minConfirmations,
                              Instant observedAt) {

    public VerifiedBalance {
        if (balance == null || sources == null || sources.isEmpty() || observedAt == null) {
            throw new ChainUnavailableException("交叉验证结论缺少必要字段");
        }
        sources = Set.copyOf(sources);
    }
}
