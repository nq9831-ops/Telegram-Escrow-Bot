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

import com.tg.escrow.common.TggException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 交易命令处理器（S2）的集成测试。
 *
 * <p>刻意<b>不 mock 中间层</b>：用真实的 {@link EscrowTradeService} + 真实的
 * {@link TradeAdmissionGate} + 内存订单存储，验证从命令文本到回执的完整路径——
 * mock 掉门禁就测不出"被拒时的原因文案是否正确接线"这类缺陷。
 */
class TradeCommandHandlerTest {

    private static final long BUYER = 1001L;
    private static final Instant T0 = Instant.parse("2026-09-23T00:00:00Z");
    private static final CommandActor ACTOR = new CommandActor(BUYER, MemberRole.MEMBER);

    /** 内存订单存储：回填自增 id，替代 JPA（无需 Spring 上下文）。 */
    private static final class InMemoryStore implements EscrowOrderStore {
        private long seq;

        @Override
        public EscrowOrder save(EscrowOrder order) {
            order.assignId(++seq);
            return order;
        }
    }

    private static BotCommand createCmd(String seller, String amount, String currency) {
        return new BotCommand("escrow", List.of("create", seller, amount, currency));
    }

    /** 用真实 gate + 真实 service 装配 handler。ctx 决定门禁看到的事实。 */
    private static TradeCommandHandler handler(int maxConcurrent, Duration cooldown,
                                               TradeAdmissionContext ctx) {
        TradeAdmissionGate gate = new TradeAdmissionGate(
                new TradeAdmissionPolicy(maxConcurrent, cooldown));
        TradeHistoryPort history = id -> new TradeAdmissionContext(
                id, ctx.activeTradeCount(), ctx.lastCompletedTradeAt(), ctx.hasUnresolvedDispute());
        Clock clock = Clock.fixed(T0, ZoneOffset.UTC);
        return new TradeCommandHandler(
                new EscrowTradeService(gate, history, new InMemoryStore(), clock));
    }

    private static TradeAdmissionContext clean() {
        return new TradeAdmissionContext(BUYER, 0, null, false);
    }

    @Test
    @DisplayName("canHandle 只认 escrow 命令")
    void canHandleOnlyEscrow() {
        TradeCommandHandler h = handler(1, Duration.ZERO, clean());

        assertThat(h.canHandle(new BotCommand("escrow", List.of("create")))).isTrue();
        assertThat(h.canHandle(new BotCommand("ban", List.of()))).isFalse();
        assertThat(h.canHandle(null)).isFalse();
    }

    @Test
    @DisplayName("成功创建 → 回执含订单号")
    void createsOrder() {
        TradeCommandHandler h = handler(1, Duration.ZERO, clean());

        assertThat(h.handle(createCmd("2002", "100", "USDT"), ACTOR)).isEqualTo("已创建订单 #1");
    }

    @Test
    @DisplayName("并发超限 → 被拒 + 原因，且不落单")
    void rejectedByConcurrentLimit() {
        TradeCommandHandler h = handler(1, Duration.ZERO,
                new TradeAdmissionContext(BUYER, 1, null, false));

        String out = h.handle(createCmd("2002", "100", "USDT"), ACTOR);

        assertThat(out).contains("被拒").contains("已有进行中的交易");
    }

    @Test
    @DisplayName("冷却期 → 被拒 + 含可重试时刻")
    void rejectedByCooldown() {
        TradeCommandHandler h = handler(5, Duration.ofHours(24),
                new TradeAdmissionContext(BUYER, 0, T0.minus(Duration.ofHours(1)), false));

        String out = h.handle(createCmd("2002", "100", "USDT"), ACTOR);

        assertThat(out).contains("被拒").contains("冷却期未满")
                .contains("可重试：").doesNotContain("暂无法预估");
    }

    @Test
    @DisplayName("争议冻结 → 被拒 + 可重试为暂无法预估")
    void rejectedByDispute() {
        TradeCommandHandler h = handler(5, Duration.ZERO,
                new TradeAdmissionContext(BUYER, 0, null, true));

        String out = h.handle(createCmd("2002", "100", "USDT"), ACTOR);

        assertThat(out).contains("被拒").contains("存在未决争议").contains("暂无法预估");
    }

    @Test
    @DisplayName("参数缺失或非法 → 返回用法说明")
    void usageOnBadArgs() {
        TradeCommandHandler h = handler(5, Duration.ZERO, clean());

        assertThat(h.handle(new BotCommand("escrow", List.of("create", "2002", "100")), ACTOR))
                .contains("用法");
        assertThat(h.handle(new BotCommand("escrow", List.of("create", "abc", "100", "USDT")), ACTOR))
                .contains("用法");
        assertThat(h.handle(new BotCommand("escrow", List.of("create", "2002", "x", "USDT")), ACTOR))
                .contains("用法");
        assertThat(h.handle(new BotCommand("escrow", List.of("create", "2002", "100", "")), ACTOR))
                .contains("用法");
    }

    @Test
    @DisplayName("自交易（卖方=买方）→ 无法创建")
    void selfTradeRejected() {
        TradeCommandHandler h = handler(5, Duration.ZERO, clean());

        assertThat(h.handle(createCmd(String.valueOf(BUYER), "100", "USDT"), ACTOR))
                .contains("无法创建");
    }

    @Test
    @DisplayName("非 escrow 命令交给 handle → 抛（分发器应先 canHandle）")
    void handleRejectsForeignCommand() {
        TradeCommandHandler h = handler(5, Duration.ZERO, clean());

        assertThatThrownBy(() -> h.handle(new BotCommand("ban", List.of()), ACTOR))
                .isInstanceOf(TggException.class);
    }
}
