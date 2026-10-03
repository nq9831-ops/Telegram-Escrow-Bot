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

/**
 * 单个交易者的信用摘要——{@code /escrow credit} 的渲染输入。
 *
 * <p>刻意带上<b>各维度</b>而不是只给一个总分：一个只报"你是银牌"的机器人无法解释
 * 「为什么我不是金牌」，用户也就无从改进。这也是 {@link TraderStats} 里那套口径
 * 唯一需要面向用户呈现的地方。
 *
 * @param userId         用户 ID（榜单展示时经 {@link RankPrivacy} 脱敏）
 * @param score          综合评分（0–100）
 * @param tier           等级
 * @param completed      完成笔数（口径见 {@link TraderStats}）
 * @param reviewCount    收到的评价条数（0 表示"暂无评价"——渲染方须如实标注，而非当作差评）
 * @param diversityShare 最大单一对手占比 [0,1]（越低越好）
 */
public record TraderCredit(long userId, int score, TraderTier.Tier tier,
                           int completed, int reviewCount, BigDecimal diversityShare) {

    public TraderCredit {
        if (userId <= 0) {
            throw new EscrowException("信用摘要：用户 ID 非法（" + userId + "）");
        }
        if (score < 0 || score > 100) {
            throw new EscrowException("信用摘要：评分必须在 0–100，实为 " + score);
        }
        if (tier == null) {
            throw new EscrowException("信用摘要：未提供等级");
        }
        if (completed < 0 || reviewCount < 0) {
            throw new EscrowException("信用摘要：计数不得为负");
        }
        if (diversityShare == null || diversityShare.signum() < 0
                || diversityShare.compareTo(BigDecimal.ONE) > 0) {
            throw new EscrowException("信用摘要：对手占比必须在 [0,1]，实为 " + diversityShare);
        }
    }
}
