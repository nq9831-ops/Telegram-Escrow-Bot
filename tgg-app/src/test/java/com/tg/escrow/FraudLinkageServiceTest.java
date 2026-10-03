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

import com.tg.escrow.core.AdminReviewNotifier;
import com.tg.escrow.core.FraudLinkage;
import com.tg.escrow.escrow.TraderStats;
import com.tg.escrow.escrow.TraderStatsPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 欺诈联动信号源（ET-04 渐进式 · 互刷信号）的行为固定测试。
 *
 * <p>口径：**只建议不执行**（FraudLinkage 语义不变），**新手前 3 笔豁免**（NewcomerBadge），
 * 单信号 = WATCHLIST（渐进式下暂只有互刷一源，≥2 信号才 BAN_RECOMMENDED）。
 */
class FraudLinkageServiceTest {

    private static final Instant T0 = Instant.parse("2026-09-30T10:00:00Z");
    private static final long BUYER = 2002L;
    private static final long SELLER = 1001L;
    private static final long CHAT = -1001234567890L;

    /** 记录投递的替身。 */
    private static final class RecordingNotifier implements AdminReviewNotifier {
        final List<String> calls = new ArrayList<>();

        @Override
        public void notifyReview(long sourceChatId, long userId,
                                com.tg.escrow.core.RiskAssessment assessment) {
        }

        @Override
        public void notifyFraudTradeRecommendation(long sourceChatId, long buyerId, long sellerId,
                                                   FraudLinkage.Action recommendation) {
            calls.add("trade:" + sourceChatId + ":" + buyerId + ":" + sellerId + ":" + recommendation);
        }
    }

    private static TraderStats stats(long userId, int completed, Map<Long, Integer> spread) {
        return new TraderStats(userId, completed, 0, completed, 0, 0, T0, T0, spread);
    }

    private static TraderStatsPort portOf(TraderStats... all) {
        Map<Long, TraderStats> byId = new java.util.HashMap<>();
        for (TraderStats s : all) {
            byId.put(s.userId(), s);
        }
        return new TraderStatsPort() {
            @Override
            public TraderStats of(long userId) {
                return byId.get(userId);
            }

            @Override
            public java.util.List<TraderStats> allWithActivity() {
                return java.util.List.copyOf(byId.values());
            }
        };
    }

    private static FraudLinkageService service(TraderStatsPort stats, RecordingNotifier notifier) {
        return new FraudLinkageService(stats, notifier,
                Clock.fixed(T0, ZoneOffset.UTC), new BigDecimal("0.6"));
    }

    @Test
    @DisplayName("双向互指均超阈值 → WATCHLIST 投递（单信号，渐进式下不升 BAN）")
    void mutualBrushSuggestsWatchlist() {
        RecordingNotifier notifier = new RecordingNotifier();
        // 双方各自 10 笔里 8 笔是对方（0.8 > 0.6 双向）——shareOf 分母是分布总和，造数须自洽
        TraderStatsPort stats = portOf(
                stats(BUYER, 10, Map.of(SELLER, 8, 999L, 2)),
                stats(SELLER, 10, Map.of(BUYER, 8, 998L, 2)));

        Optional<FraudLinkage.Action> action = service(stats, notifier).assess(CHAT, BUYER, SELLER);

        assertThat(action).contains(FraudLinkage.Action.WATCHLIST);
        assertThat(notifier.calls).containsExactly("trade:" + CHAT + ":" + BUYER + ":" + SELLER + ":WATCHLIST");
    }

    @Test
    @DisplayName("新手豁免（任一方 <3 笔）→ 不判不投（前 3 笔豁免互刷检测，ET-38）")
    void newcomersAreExempt() {
        RecordingNotifier notifier = new RecordingNotifier();
        TraderStatsPort stats = portOf(
                stats(BUYER, 2, Map.of(SELLER, 2)),     // 新手：2 笔全是对方也不判
                stats(SELLER, 10, Map.of(BUYER, 8, 998L, 2)));

        assertThat(service(stats, notifier).assess(CHAT, BUYER, SELLER)).isEmpty();
        assertThat(notifier.calls).isEmpty();
    }

    @Test
    @DisplayName("单向集中或未超阈值 → 不投（双向互指是判据，不是单向）")
    void oneSidedConcentrationIsNotFlagged() {
        RecordingNotifier notifier = new RecordingNotifier();
        // 买方集中于卖方（8/10=0.8），卖方对买方占比低（1/10=0.1）——单向不算互刷
        TraderStatsPort stats = portOf(
                stats(BUYER, 10, Map.of(SELLER, 8, 999L, 2)),
                stats(SELLER, 10, Map.of(BUYER, 1, 998L, 9)));

        assertThat(service(stats, notifier).assess(CHAT, BUYER, SELLER)).isEmpty();
        assertThat(notifier.calls).isEmpty();
    }

    @Test
    @DisplayName("无统计记录（新对手）→ 不判（没有数据不等于可疑）")
    void missingStatsIsNotFlagged() {
        RecordingNotifier notifier = new RecordingNotifier();

        assertThat(service(portOf(), notifier).assess(CHAT, BUYER, SELLER)).isEmpty();
        assertThat(notifier.calls).isEmpty();
    }
}
