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

import com.tg.escrow.core.TradeGroupPort;
import com.tg.escrow.escrow.TradeGroup;
import com.tg.escrow.escrow.TradeGroupService;
import com.tg.escrow.escrow.TradeGroupStore;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

/**
 * 交易群件的测试替身（空 store + 记录动作的 port）——供不测群行为的测试类构造 {@code TradeCommandHandler}。
 *
 * <p>{@code TradeGroupService} 是 final 类（不可 mock），测试用真实实例 + 空 store：
 * 未绑定时群侧编排是空操作，正好表达"未建群不影响交易主流程"的降级语义。
 */
final class StubTradeGroup {

    private StubTradeGroup() {
    }

    /** 空 store：所有查找返空、保存原样返回——群侧编排在这些测试里应是空操作。 */
    static TradeGroupService service(Clock clock) {
        return new TradeGroupService(new TradeGroupStore() {
            @Override
            public Optional<TradeGroup> find(long tradeId) {
                return Optional.empty();
            }

            @Override
            public Optional<TradeGroup> findByChatId(long chatId) {
                return Optional.empty();
            }

            @Override
            public TradeGroup save(TradeGroup group) {
                return group;
            }
        }, Duration.ofDays(7), clock);
    }

    /** 带状态的内存 store：可绑定、可结算、可反查——交易群行为测试用。 */
    static MapStore mapStore() {
        return new MapStore();
    }

    /** 用指定 store 的 service（行为测试需要自己持 store 句柄断言状态）。 */
    static TradeGroupService service(TradeGroupStore store, Clock clock) {
        return new TradeGroupService(store, Duration.ofDays(7), clock);
    }

    static final class MapStore implements TradeGroupStore {
        private final java.util.Map<Long, TradeGroup> rows = new java.util.LinkedHashMap<>();

        @Override
        public Optional<TradeGroup> find(long tradeId) {
            return Optional.ofNullable(rows.get(tradeId));
        }

        @Override
        public Optional<TradeGroup> findByChatId(long chatId) {
            return rows.values().stream()
                    .filter(g -> Long.valueOf(chatId).equals(g.getChatId()))
                    .findFirst();
        }

        @Override
        public TradeGroup save(TradeGroup group) {
            rows.put(group.getTradeId(), group);
            return group;
        }
    }

    /** 记录动作的 port：announce 返固定消息 ID，pin/leave 记录在 {@code actions}。 */
    static RecordingPort port() {
        return new RecordingPort();
    }

    static final class RecordingPort implements TradeGroupPort {
        final java.util.List<String> actions = new java.util.ArrayList<>();

        @Override
        public int announce(long chatId, String text) {
            actions.add("announce:" + chatId + ":" + text);
            return 1;
        }

        @Override
        public void pin(long chatId, int messageId) {
            actions.add("pin:" + chatId + ":" + messageId);
        }

        @Override
        public void leave(long chatId) {
            actions.add("leave:" + chatId);
        }
    }
}
