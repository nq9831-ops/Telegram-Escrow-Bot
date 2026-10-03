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

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

/** 欺诈联动的测试替身：空统计 = 评估永远 empty（不判不投），供不测欺诈的测试构造 handler。 */
final class StubFraudLinkage {

    private StubFraudLinkage() {
    }

    static FraudLinkageService service() {
        return new FraudLinkageService(
                new TraderStatsPort() {
                    @Override
                    public TraderStats of(long userId) {
                        return null;
                    }

                    @Override
                    public List<TraderStats> allWithActivity() {
                        return List.of();
                    }
                },
                new AdminReviewNotifier() {
                    @Override
                    public void notifyReview(long sourceChatId, long userId,
                                            com.tg.escrow.core.RiskAssessment assessment) {
                    }

                    @Override
                    public void notifyFraudTradeRecommendation(long sourceChatId, long buyerId,
                                                               long sellerId, FraudLinkage.Action r) {
                    }
                },
                Clock.fixed(Instant.parse("2026-09-30T10:00:00Z"), ZoneOffset.UTC),
                new BigDecimal("0.6"));
    }
}
