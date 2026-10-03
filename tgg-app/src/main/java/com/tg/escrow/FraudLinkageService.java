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
import com.tg.escrow.escrow.MutualBrushDetector;
import com.tg.escrow.escrow.NewcomerBadge;
import com.tg.escrow.escrow.TraderStats;
import com.tg.escrow.escrow.TraderStatsPort;
import com.tg.escrow.common.TggException;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Optional;

/**
 * 欺诈联动的信号源编排（ET-04 渐进式 · 第一源：互刷）——交易达成后评估并向管理员投递建议。
 *
 * <p><b>渐进式口径（拍板③）</b>：只接有真实采集点的信号——互刷（交易行为）在此评估；
 * 设备关联/异常检测两源待其采集点落位后并入 {@link FraudLinkage#recommend}。
 * 单信号 = {@code WATCHLIST}（关注），≥2 信号才 {@code BAN_RECOMMENDED}——不硬造第二信号。
 *
 * <p><b>新手豁免（ET-38）</b>：任一方完成笔数 &lt; {@code NewcomerBadge.NEWCOMER_THRESHOLD}
 * （前 3 笔）不判互刷——冷启动期对手集中是常态，判了全是误报。
 *
 * <p><b>只建议不执行</b>：本类只投递建议（{@link AdminReviewNotifier}），
 * 执行仍走 {@code ModerationOrchestrator}（人工核实后处置）。
 */
public final class FraudLinkageService {

    private final TraderStatsPort stats;
    private final AdminReviewNotifier notifier;
    private final BigDecimal concentrationThreshold;

    public FraudLinkageService(TraderStatsPort stats, AdminReviewNotifier notifier,
                               Clock clock, BigDecimal concentrationThreshold) {
        if (stats == null || clock == null || concentrationThreshold == null) {
            throw new TggException("欺诈联动：统计端口、时钟与集中度阈值均不可为空");
        }
        // notifier 可空：null = 投递能力未配置（同 review 通道三态）——评估照做、不投递、不谎报
        if (concentrationThreshold.signum() <= 0
                || concentrationThreshold.compareTo(BigDecimal.ONE) > 0) {
            throw new TggException("欺诈联动：集中度阈值须在 (0,1]，实为 " + concentrationThreshold);
        }
        this.stats = stats;
        this.notifier = notifier;
        this.concentrationThreshold = concentrationThreshold;
    }

    /**
     * 交易达成后的欺诈评估：豁免 → 互刷判定 → 建议投递。
     *
     * @param sourceChatId 交易群 chat ID（建议的来源上下文，供管理员定位）
     * @param buyerId      买方
     * @param sellerId     卖方
     * @return 建议动作（{@code empty} = 无需建议：豁免 / 无数据 / 未达阈值）
     */
    public Optional<FraudLinkage.Action> assess(long sourceChatId, long buyerId, long sellerId) {
        TraderStats a = stats.of(buyerId);
        TraderStats b = stats.of(sellerId);
        if (a == null || b == null) {
            // 没有统计不等于可疑——数据不足不判
            return Optional.empty();
        }
        if (NewcomerBadge.isMutualBrushExempt(a.completed())
                || NewcomerBadge.isMutualBrushExempt(b.completed())) {
            return Optional.empty();
        }
        if (!MutualBrushDetector.isSuspicious(buyerId, a.counterpartyCounts(),
                sellerId, b.counterpartyCounts(), concentrationThreshold)) {
            return Optional.empty();
        }
        // 渐进式：当前只接互刷一源（mutualBrush=true，其余两源无采集点不硬造）
        FraudLinkage.Action action = FraudLinkage.recommend(true, false, false);
        if (notifier != null) {
            notifier.notifyFraudTradeRecommendation(sourceChatId, buyerId, sellerId, action);
        }
        return Optional.of(action);
    }
}
