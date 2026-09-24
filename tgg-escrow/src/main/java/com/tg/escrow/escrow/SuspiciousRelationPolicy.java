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

import com.tg.escrow.common.EscrowException;
import com.tg.escrow.escrow.VoterRegistry.Tier;

/**
 * 可疑关系降级（ET-68：标记后限制投票资格）。
 *
 * <p>被标记可疑（互刷/设备关联等）→ 投票资格<b>降为观察员</b>（可讨论、无投票权）——
 * 与 {@code PenaltyPlanner.VOTE_SUSPEND} 方向一致但更轻：撤销票权、不撤销参与（保留申诉通道）。
 * 未标记原样返回。纯函数。
 */
public final class SuspiciousRelationPolicy {

    private SuspiciousRelationPolicy() {
    }

    /**
     * 计算生效层级。
     *
     * @param baseTier 原层级
     * @param flagged  是否被标记可疑
     * @return 标记时降为 {@link Tier#OBSERVER}；否则原层级
     */
    public static Tier effective(Tier baseTier, boolean flagged) {
        if (baseTier == null) {
            throw new EscrowException("可疑降级：原层级不可为空");
        }
        return flagged ? Tier.OBSERVER : baseTier;
    }
}
