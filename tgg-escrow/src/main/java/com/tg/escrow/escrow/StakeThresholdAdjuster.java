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

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 仲裁员加入费动态门槛（ET-46）：合格投票人稀少时降低质押门槛，避免投票人枯竭。
 *
 * <h2>规则（阈值语义定死）</h2>
 * <p>合格投票人数<b>低于预期的一半</b>（"低于一半"不含本数）→ 质押减半；
 * 否则不打折。两条不变量：
 * <ul>
 *   <li><b>减半不破质押下限</b> {@link VoteStake#MIN}（1 USDT）——门槛再低也要有成本，
 *       否则刷票无代价；</li>
 *   <li>人数充足时一律原价——折扣只用于拉人，不用于常态减负。</li>
 * </ul>
 *
 * <p>纯函数、无状态。
 */
public final class StakeThresholdAdjuster {

    private StakeThresholdAdjuster() {
    }

    /**
     * 计算调整后的质押门槛。
     *
     * @param baseStake      基准质押（必须为正）
     * @param eligibleVoters 合格投票人数（不得为负）
     * @param expectedVoters 预期参与人数（必须为正）
     * @return 人数低于预期一半时 {@code base/2}（下限 {@link VoteStake#MIN}），否则原值
     */
    public static BigDecimal adjust(BigDecimal baseStake, int eligibleVoters, int expectedVoters) {
        if (baseStake == null || baseStake.signum() <= 0) {
            throw new EscrowException("动态门槛：基准质押必须为正，实为 " + baseStake);
        }
        if (eligibleVoters < 0) {
            throw new EscrowException("动态门槛：合格投票人数不得为负，实为 " + eligibleVoters);
        }
        if (expectedVoters <= 0) {
            throw new EscrowException("动态门槛：预期参与人数必须为正，实为 " + expectedVoters);
        }
        if (eligibleVoters * 2 >= expectedVoters) {
            // 恰好一半不打折（"低于一半"不含本数）
            return baseStake;
        }
        BigDecimal halved = baseStake.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_DOWN);
        return halved.max(VoteStake.MIN);
    }
}
