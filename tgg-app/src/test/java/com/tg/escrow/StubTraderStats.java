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
package com.tg.escrow;

import com.tg.escrow.escrow.TraderStats;
import com.tg.escrow.escrow.TraderStatsPort;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link TraderStatsPort} 的测试替身。
 *
 * <p>{@link #EMPTY} 表示"账上没有任何成交史"——这是绝大多数与榜单无关的用例需要的默认值：
 * 它们构造 {@code TradeCommandHandler} 只是为了测别的命令，给一个空统计即可，
 * 不该因此去搭一整套造数。
 *
 * <p>需要真实榜单内容的用例（如 {@code /escrow rank} 的验收）用带数据的构造器；
 * 需要"某个用户自己的统计"的用例（如 {@code /escrow credit}）同样如此——{@code of()} 会
 * 从同一批数据里按 ID 取，取不到才回落到空统计。
 *
 * <p>端口本身没有可观察的写行为，故这里只实现读，不做调用记录。
 */
final class StubTraderStats implements TraderStatsPort {

    /** 常用实例：无任何成交史（{@code of()} 返回空统计、{@code allWithActivity()} 为空）。 */
    static final StubTraderStats EMPTY = new StubTraderStats(List.of());

    private final List<TraderStats> all;
    private final Map<Long, TraderStats> byUser;

    StubTraderStats(List<TraderStats> all) {
        this.all = List.copyOf(all);
        Map<Long, TraderStats> index = new HashMap<>();
        for (TraderStats stats : this.all) {
            index.put(stats.userId(), stats);
        }
        this.byUser = Map.copyOf(index);
    }

    @Override
    public TraderStats of(long userId) {
        // 与端口契约一致：无成交史的用户返回**空统计**，不是 null、也不抛
        return byUser.getOrDefault(userId,
                new TraderStats(userId, 0, 0, 0, 0, 0, null, null, Map.of()));
    }

    @Override
    public List<TraderStats> allWithActivity() {
        return all;
    }
}
