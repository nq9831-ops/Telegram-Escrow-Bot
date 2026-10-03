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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 榜单渲染（ET-77/78/79 的推送内容生成）：周榜 Top 10 / 月榜 Top 20 / 季度荣誉榜共用。
 *
 * <p>展示口径（13 号材料）：奖牌前缀 + 等级徽标 + 统计；<b>匿名榜不泄用户名</b>——
 * 由 {@link RankPrivacy} 按用户公开设置展示。条数截断到 {@code topN}，空榜明示"暂无数据"。
 */
public final class Leaderboard {

    /**
     * 榜单条目。
     *
     * @param username      用户名
     * @param score         综合评分
     * @param tier          等级
     * @param publicProfile 是否公开用户名
     */
    public record Entry(String username, int score, TraderTier.Tier tier, boolean publicProfile) {
    }

    private static final String[] MEDALS = {"🥇", "🥈", "🥉"};

    private Leaderboard() {
    }

    /**
     * 渲染榜单文案。
     *
     * @param entries   条目（无需预排序；按评分降序渲染）
     * @param topN      截断条数（≥1）
     * @param anonymize 是否强制匿名（匿名榜整体不显示用户名，即使用户已公开）
     */
    public static String render(List<Entry> entries, int topN, boolean anonymize) {
        if (entries == null) {
            throw new EscrowException("榜单渲染：条目列表未提供");
        }
        if (topN < 1) {
            throw new EscrowException("榜单渲染：条数必须 ≥ 1，实为 " + topN);
        }
        List<Entry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparingInt(Entry::score).reversed());
        if (sorted.isEmpty()) {
            return "📊 本期榜单：暂无数据";
        }
        StringBuilder sb = new StringBuilder("📊 本期交易排行\n");
        int shown = Math.min(topN, sorted.size());
        for (int i = 0; i < shown; i++) {
            Entry e = sorted.get(i);
            String name = anonymize
                    ? RankPrivacy.display(e.username(), false)
                    : RankPrivacy.display(e.username(), e.publicProfile());
            sb.append(i < MEDALS.length ? MEDALS[i] : "  ").append(' ')
                    .append(name).append("  ").append(e.tier().badge()).append('\n')
                    .append("   评分 ").append(e.score()).append('\n');
        }
        return sb.toString();
    }
}
