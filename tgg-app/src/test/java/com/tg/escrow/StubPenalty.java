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

import com.tg.escrow.escrow.DisputeLoss;
import com.tg.escrow.escrow.DisputeLossStore;
import com.tg.escrow.escrow.PenaltyService;
import com.tg.escrow.moderation.WarningCountQuery;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** 惩罚编排测试替身：内存败诉台账 + 恒 0 警告（需要非 0 警告时直接构造 PenaltyService）。 */
final class StubPenalty {

    private StubPenalty() {
    }

    static PenaltyService service(Clock clock) {
        return new PenaltyService(new MapStore(), zeroWarnings(), clock);
    }

    /** 恒 0 警告的计数口。 */
    static WarningCountQuery zeroWarnings() {
        return userId -> 0;
    }

    static MapStore mapStore() {
        return new MapStore();
    }

    /** 内存败诉台账。 */
    static final class MapStore implements DisputeLossStore {

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
