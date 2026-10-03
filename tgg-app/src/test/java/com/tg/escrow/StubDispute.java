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

import com.tg.escrow.escrow.DisputeSession;
import com.tg.escrow.escrow.DisputeSessionStore;
import com.tg.escrow.escrow.EscrowDisputeService;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 争议会话测试替身：内存存储 + 固定 24h 证据窗口。
 *
 * <p>与 {@code StubTradeGroup} 同族——把编排所需的存储换成可断言的内存实现，
 * 让 {@code TradeCommandHandler} 的装配在单测里可跑（不触库）。
 */
final class StubDispute {

    /** 证据窗口固定 24h（与生产缺省一致，便于断言 deadline）。 */
    static final Duration WINDOW = Duration.ofHours(24);

    private StubDispute() {
    }

    /** 每个 handler 装配用一份新的内存存储。 */
    static EscrowDisputeService service(Clock clock) {
        return new EscrowDisputeService(new MapStore(), WINDOW, clock);
    }

    /** 可传入预制存储（需要跨装配断言会话状态时用）。 */
    static EscrowDisputeService service(MapStore store, Clock clock) {
        return new EscrowDisputeService(store, WINDOW, clock);
    }

    static MapStore mapStore() {
        return new MapStore();
    }

    /** 内存争议会话存储。 */
    static final class MapStore implements DisputeSessionStore {

        private final Map<Long, DisputeSession> rows = new LinkedHashMap<>();

        @Override
        public Optional<DisputeSession> find(long orderId) {
            return Optional.ofNullable(rows.get(orderId));
        }

        @Override
        public DisputeSession save(DisputeSession session) {
            rows.put(session.getOrderId(), session);
            return session;
        }
    }
}
