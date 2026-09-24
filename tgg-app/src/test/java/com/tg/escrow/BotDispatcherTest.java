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

import com.tg.escrow.core.BannedWordRegistry;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bot 命令分发器（S1 命令路由 + GM-06 违禁词接线）的行为固定测试。
 *
 * <p>路由：非命令 → 违禁词检查（<b>命中给出处置回执</b>——热更新词库的真实生产消费者）→
 * 不命中不响应；escrow 命令 → 交易处理器；其它命令 → 帮助。
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

    private static BotDispatcher dispatcher(BannedWordRegistry registry) {
        TradeAdmissionGate gate = new TradeAdmissionGate(new TradeAdmissionPolicy(5, Duration.ZERO));
        TradeHistoryPort history = id -> new TradeAdmissionContext(id, 0, null, false);
        EscrowTradeService service = new EscrowTradeService(gate, history,
                new InMemoryStore(), Clock.fixed(T0, ZoneOffset.UTC));
        return new BotDispatcher(new TradeCommandHandler(service, new com.tg.escrow.escrow.AmountTierPolicy(
                        new java.math.BigDecimal("100"), new java.math.BigDecimal("1000"))),
                "mybot", registry, new com.tg.escrow.core.KeywordAutoReply());
    }

    private static BotDispatcher dispatcher(com.tg.escrow.core.KeywordAutoReply autoReply) {
        TradeAdmissionGate gate = new TradeAdmissionGate(new TradeAdmissionPolicy(5, Duration.ZERO));
        TradeHistoryPort history = id -> new TradeAdmissionContext(id, 0, null, false);
        EscrowTradeService service = new EscrowTradeService(gate, history,
                new InMemoryStore(), Clock.fixed(T0, ZoneOffset.UTC));
        return new BotDispatcher(new TradeCommandHandler(service, new com.tg.escrow.escrow.AmountTierPolicy(
                        new java.math.BigDecimal("100"), new java.math.BigDecimal("1000"))),
                "mybot", new BannedWordRegistry(), autoReply);
    }

    private static BotDispatcher dispatcher() {
        return dispatcher(new BannedWordRegistry());
    }

    @Test
    @DisplayName("自动回复：普通消息命中关键词 → 返回预设回复（KeywordAutoReply 的生产消费者）")
    void autoReplyHit() {
        com.tg.escrow.core.KeywordAutoReply autoReply = new com.tg.escrow.core.KeywordAutoReply();
        autoReply.register("怎么收费", "平台费默认 0%。");

        String reply = dispatcher(autoReply).handle(100L, "请问 怎么收费", ACTOR);

        assertThat(reply).isEqualTo("平台费默认 0%。");
    }

    @Test
    @DisplayName("违禁词优先于自动回复（同时命中给处置回执）")
    void bannedWordBeatsAutoReply() {
        com.tg.escrow.core.KeywordAutoReply autoReply = new com.tg.escrow.core.KeywordAutoReply();
        autoReply.register("广告词", "自动回复");
        BannedWordRegistry registry = new BannedWordRegistry();
        registry.reload(100L, java.util.List.of("广告词"), java.util.List.of());
        com.tg.escrow.core.KeywordAutoReply unused = autoReply; // 同名关键词场景
        TradeAdmissionGate gate = new TradeAdmissionGate(new TradeAdmissionPolicy(5, Duration.ZERO));
        TradeHistoryPort history = id -> new TradeAdmissionContext(id, 0, null, false);
        EscrowTradeService service = new EscrowTradeService(gate, history,
                new InMemoryStore(), Clock.fixed(T0, ZoneOffset.UTC));
        BotDispatcher d = new BotDispatcher(new TradeCommandHandler(service,
                new com.tg.escrow.escrow.AmountTierPolicy(new java.math.BigDecimal("100"),
                        new java.math.BigDecimal("1000"))), "mybot", registry, unused);

        String reply = d.handle(100L, "看这个 广告词", ACTOR);

        assertThat(reply).contains("违禁");
    }

    @Test
    @DisplayName("非命令消息（无违禁词）→ 不响应（返回 null）")
    void plainMessageGetsNoReply() {
        assertThat(dispatcher().handle(100L, "大家好，这单怎么走", ACTOR)).isNull();
    }

    @Test
    @DisplayName("违禁词命中 → 处置回执（含规则名）——热更新词库的真实消费者")
    void bannedWordHitGetsReply() {
        BannedWordRegistry registry = new BannedWordRegistry();
        registry.reload(100L, List.of("赌博广告"), List.of());

        String reply = dispatcher(registry).handle(100L, "这里有 赌博广告 快来", ACTOR);

        assertThat(reply).contains("违禁").contains("赌博广告");
    }

    @Test
    @DisplayName("违禁词按群隔离：A 群的词在 B 群不触发")
    void bannedWordPerGuild() {
        BannedWordRegistry registry = new BannedWordRegistry();
        registry.reload(100L, List.of("赌博广告"), List.of());

        assertThat(dispatcher(registry).handle(200L, "这里有 赌博广告", ACTOR)).isNull();
    }

    @Test
    @DisplayName("escrow 命令 → 路由到交易处理器，回执含订单号")
    void escrowCommandRouted() {
        String reply = dispatcher().handle(100L, "/escrow confirm 2002 100 USDT", ACTOR);

        assertThat(reply).contains("已创建订单");
    }

    @Test
    @DisplayName("其它命令 → 帮助回执（含用法）")
    void unknownCommandGetsHelp() {
        String reply = dispatcher().handle(100L, "/whatever", ACTOR);

        assertThat(reply).contains("escrow").contains("用法");
    }

    @Test
    @DisplayName("@botname 后缀：发给别的 bot 的命令不响应")
    void commandForOtherBotIgnored() {
        assertThat(dispatcher().handle(100L, "/escrow@otherbot confirm 2002 100 USDT", ACTOR)).isNull();
    }

    @Test
    @DisplayName("null 文本 / null actor → 不响应（不抛，群里什么都有）")
    void nullsGetNoReply() {
        BotDispatcher d = dispatcher();

        assertThat(d.handle(100L, null, ACTOR)).isNull();
        assertThat(d.handle(100L, "/escrow confirm 2002 100 USDT", null)).isNull();
    }
}
