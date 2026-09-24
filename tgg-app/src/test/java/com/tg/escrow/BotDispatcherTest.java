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

import com.tg.escrow.core.BotCommand;
import com.tg.escrow.core.CommandActor;
import com.tg.escrow.core.MemberRole;
import com.tg.escrow.escrow.EscrowOrder;
import com.tg.escrow.escrow.EscrowOrderStore;
import com.tg.escrow.escrow.EscrowTradeService;
import com.tg.escrow.escrow.TradeAdmissionContext;
import com.tg.escrow.escrow.TradeAdmissionGate;
import com.tg.escrow.escrow.TradeAdmissionPolicy;
import com.tg.escrow.escrow.TradeHistoryPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bot 分发器（S1 命令路由）的行为固定测试。
 *
 * <p>分发语义：<b>非命令 → 不响应（null）</b>（群里绝大多数消息与 Bot 无关）；
 * escrow 命令 → {@link TradeCommandHandler}；已解析的其它命令 → 帮助回执。
 * 用真实 TradeCommandHandler + 内存存储装配——mock 掉路由就测不出接线错位。
 */
class BotDispatcherTest {

    private static final Instant T0 = Instant.parse("2026-09-23T00:00:00Z");
    private static final CommandActor ACTOR = new CommandActor(1001L, MemberRole.MEMBER);

    private static final class InMemoryStore implements EscrowOrderStore {
        private long seq;

        @Override
        public EscrowOrder save(EscrowOrder order) {
            order.assignId(++seq);
            return order;
        }
    }

    private static BotDispatcher dispatcher() {
        TradeAdmissionGate gate = new TradeAdmissionGate(new TradeAdmissionPolicy(5, Duration.ZERO));
        TradeHistoryPort history = id -> new TradeAdmissionContext(id, 0, null, false);
        EscrowTradeService service = new EscrowTradeService(gate, history,
                new InMemoryStore(), Clock.fixed(T0, ZoneOffset.UTC));
        return new BotDispatcher(new TradeCommandHandler(service), "mybot");
    }

    @Test
    @DisplayName("非命令消息 → 不响应（返回 null）")
    void plainMessageGetsNoReply() {
        assertThat(dispatcher().handle("大家好，这单怎么走", ACTOR)).isNull();
    }

    @Test
    @DisplayName("escrow 命令 → 路由到交易处理器，回执含订单号")
    void escrowCommandRouted() {
        String reply = dispatcher().handle("/escrow create 2002 100 USDT", ACTOR);

        assertThat(reply).contains("已创建订单");
    }

    @Test
    @DisplayName("其它命令 → 帮助回执（含用法）")
    void unknownCommandGetsHelp() {
        String reply = dispatcher().handle("/whatever", ACTOR);

        assertThat(reply).contains("escrow").contains("用法");
    }

    @Test
    @DisplayName("@botname 后缀：发给别的 bot 的命令不响应")
    void commandForOtherBotIgnored() {
        assertThat(dispatcher().handle("/escrow@otherbot create 2002 100 USDT", ACTOR)).isNull();
    }

    @Test
    @DisplayName("发给本 bot 的 @ 后缀命令正常路由")
    void commandForThisBotRouted() {
        String reply = dispatcher().handle("/escrow@mybot create 2002 100 USDT", ACTOR);

        assertThat(reply).contains("已创建订单");
    }

    @Test
    @DisplayName("null 文本 / null actor → 不响应（不抛，群里什么都有）")
    void nullsGetNoReply() {
        BotDispatcher d = dispatcher();

        assertThat(d.handle(null, ACTOR)).isNull();
        assertThat(d.handle("/escrow create 2002 100 USDT", null)).isNull();
    }
}
