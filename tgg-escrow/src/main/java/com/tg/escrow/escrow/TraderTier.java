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

/**
 * 交易者等级评定（ET-76）：💎钻石 / 🥇金牌 / 🥈银牌 / 🥉铜牌 / 🌱新手。
 *
 * <h2>等级冲突定案：笔数门槛优先</h2>
 * <p>13 号材料的条件自相矛盾（"评分 90 但只有 4 笔"既够钻石又是新手）。定案：
 * <b>未满 5 笔一律新手</b>——笔数是事实、评分是派生，且高分可由少数交易偶然刷出；
 * 满 5 笔但信用不足 60 的也归新手（五等级中的兜底档）。
 */
public final class TraderTier {

    /** 交易者等级。 */
    public enum Tier {
        /** 新手（&lt;5 笔，或满 5 笔但信用不足）。 */
        NEWBIE("🌱"),
        /** 铜牌（≥5 笔且评分 ≥60）。 */
        BRONZE("🥉"),
        /** 银牌（≥10 笔且评分 ≥70）。 */
        SILVER("🥈"),
        /** 金牌（≥20 笔且评分 ≥80）。 */
        GOLD("🥇"),
        /** 钻石（≥50 笔且评分 ≥90）。 */
        DIAMOND("💎");

        private final String badge;

        Tier(String badge) {
            this.badge = badge;
        }

        /** 等级徽标。 */
        public String badge() {
            return badge;
        }
    }

    private TraderTier() {
    }

    /**
     * 评定等级（笔数门槛优先于评分）。
     *
     * @param score     综合评分（0–100）
     * @param completed 完成笔数（≥0）
     */
    public static Tier tierOf(int score, int completed) {
        if (completed < 0) {
            throw new EscrowException("等级评定：完成笔数不得为负，实为 " + completed);
        }
        if (completed < 5) {
            return Tier.NEWBIE;
        }
        if (completed >= 50 && score >= 90) {
            return Tier.DIAMOND;
        }
        if (completed >= 20 && score >= 80) {
            return Tier.GOLD;
        }
        if (completed >= 10 && score >= 70) {
            return Tier.SILVER;
        }
        return score >= 60 ? Tier.BRONZE : Tier.NEWBIE;
    }
}
