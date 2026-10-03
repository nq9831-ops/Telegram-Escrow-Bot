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

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 惩罚编排（ET-61）用户级行为：败诉方口径、记罚幂等、计划组合。
 *
 * <p>败诉口径由用户 2026-10-02 拍板：裁决 outcome 对其不利的一方即败诉方。
 * 组合阈值取自 {@link PenaltyPlanner}：警告 ≥3 → TRADE_LIMIT + COOLDOWN_EXTEND；
 * 败诉 ≥2 → VOTE_SUSPEND；可疑 → PUBLIC_NOTICE。
 */
class PenaltyServiceTest {

    private static final long BUYER = 11L;
    private static final long SELLER = 22L;
    private static final Instant T0 = Instant.parse("2026-10-02T00:00:00Z");

    private static EscrowOrder order(long id) {
        EscrowOrder order = new EscrowOrder(BUYER, SELLER, new BigDecimal("10"), "TON", T0);
        order.assignId(id);
        return order;
    }

    private static PenaltyService service(DisputeLossStore store, int warnCount, Instant now) {
        return new PenaltyService(store, userId -> warnCount, Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void releaseMeansBuyerLostRefundMeansSellerLost() {
        EscrowOrder o = order(1);

        assertEquals(BUYER, PenaltyService.loserOf(o, EscrowVerdict.Outcome.RELEASE),
                "RELEASE 放款卖方 → 卖方胜、买方败");
        assertEquals(SELLER, PenaltyService.loserOf(o, EscrowVerdict.Outcome.REFUND),
                "REFUND 退款买方 → 买方胜、卖方败");
    }

    @Test
    void recordingIsIdempotentPerOrder() {
        StubStore store = new StubStore();
        EscrowOrder o = order(2);
        PenaltyService svc = service(store, 0, T0);

        DisputeLoss first = svc.recordDisputeLoss(o, EscrowVerdict.Outcome.REFUND);
        DisputeLoss second = svc.recordDisputeLoss(o, EscrowVerdict.Outcome.RELEASE);

        assertSame(first, second, "同一订单重复裁决返回同一条记录（不重复记罚）");
        assertEquals(SELLER, first.getLoserUserId());
        assertEquals(1, store.rows.size());
    }

    @Test
    void lossesAccumulatePerLoserOnly() {
        StubStore store = new StubStore();
        PenaltyService svc = service(store, 0, T0);

        svc.recordDisputeLoss(order(3), EscrowVerdict.Outcome.REFUND);   // 卖方败
        svc.recordDisputeLoss(order(4), EscrowVerdict.Outcome.REFUND);   // 卖方败
        svc.recordDisputeLoss(order(5), EscrowVerdict.Outcome.RELEASE);  // 买方败

        assertEquals(2, svc.lossesOf(SELLER));
        assertEquals(1, svc.lossesOf(BUYER));
    }

    @Test
    void planCombinesWarningsAndLosses() {
        StubStore store = new StubStore();
        // 警告 3 → TRADE_LIMIT + COOLDOWN_EXTEND；败诉 2 → VOTE_SUSPEND（无标记 → 无 PUBLIC_NOTICE）
        PenaltyService svc = service(store, 3, T0);
        svc.recordDisputeLoss(order(6), EscrowVerdict.Outcome.REFUND);
        svc.recordDisputeLoss(order(7), EscrowVerdict.Outcome.REFUND);

        EnumSet<PenaltyPlanner.Penalty> plan = svc.planOf(SELLER);

        assertTrue(plan.contains(PenaltyPlanner.Penalty.TRADE_LIMIT));
        assertTrue(plan.contains(PenaltyPlanner.Penalty.COOLDOWN_EXTEND));
        assertTrue(plan.contains(PenaltyPlanner.Penalty.VOTE_SUSPEND));
        assertFalse(plan.contains(PenaltyPlanner.Penalty.PUBLIC_NOTICE), "无标记则无公示");
    }

    @Test
    void noHistoryMeansNoPenalty() {
        assertTrue(service(new StubStore(), 0, T0).planOf(BUYER).isEmpty(),
                "零违规 → 空计划（不预罚）");
    }

    /** 内存败诉台账（另附 countByLoser 的口径实现）。 */
    private static final class StubStore implements DisputeLossStore {

        private final Map<Long, DisputeLoss> rows = new LinkedHashMap<>();

        @Override
        public Optional<DisputeLoss> find(long orderId) {
            return Optional.ofNullable(rows.get(orderId));
        }

        @Override
        public DisputeLoss save(DisputeLoss loss) {
            rows.put(loss.getOrderId(), loss);
            return loss;
        }

        @Override
        public int countByLoser(long loserUserId) {
            int n = 0;
            for (DisputeLoss row : rows.values()) {
                if (row.getLoserUserId() == loserUserId) {
                    n++;
                }
            }
            return n;
        }
    }
}
