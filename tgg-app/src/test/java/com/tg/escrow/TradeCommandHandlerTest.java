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
import com.tg.escrow.escrow.AmountTierPolicy;
import com.tg.escrow.escrow.EscrowOrder;
import com.tg.escrow.escrow.EscrowOrderLookupPort;
import com.tg.escrow.escrow.EscrowOrderStore;
import com.tg.escrow.escrow.EscrowTradeService;
import com.tg.escrow.escrow.PendingTradeRegistry;
import com.tg.escrow.escrow.TradeAdmissionContext;
import com.tg.escrow.escrow.TradeAdmissionGate;
import com.tg.escrow.escrow.TradeAdmissionPolicy;
import com.tg.escrow.escrow.TradeHistoryPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
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
 * {@link TradeAdmissionGate} + 内存订单存储 + 真实 {@link PendingTradeRegistry}，
 * 验证从命令文本到回执的完整路径——mock 掉门禁/登记就测不出"被拒原因文案"与
 * "ET-34 提示不可绕过"这类接线缺陷。
 */
class TradeCommandHandlerTest {

    private static final long BUYER = 1001L;
    private static final Instant T0 = Instant.parse("2026-09-23T00:00:00Z");
    private static final CommandActor ACTOR = new CommandActor(BUYER, MemberRole.MEMBER);
    private static final Duration PENDING_TTL = Duration.ofMinutes(10);

    /**
     * 内存订单存储 + 查询端口：回填自增 id 并留档，替代 JPA（无需 Spring 上下文）。
     * 同时实现 {@link EscrowOrderLookupPort}，让 T2 状态查询走真实查询路径而非特判。
     */
    private static final class InMemoryStore implements EscrowOrderStore, EscrowOrderLookupPort {
        private final java.util.Map<Long, EscrowOrder> byId = new java.util.HashMap<>();
        private long seq;

        @Override
        public EscrowOrder save(EscrowOrder order) {
            order.assignId(++seq);
            byId.put(order.getId(), order);
            return order;
        }

        @Override
        public java.util.Optional<EscrowOrder> byId(long orderId) {
            return java.util.Optional.ofNullable(byId.get(orderId));
        }
    }

    private static BotCommand createCmd(String seller, String amount, String currency) {
        return new BotCommand("escrow", List.of("create", seller, amount, currency));
    }

    private static BotCommand confirmCmd(String seller, String amount, String currency) {
        return new BotCommand("escrow", List.of("confirm", seller, amount, currency));
    }

    private static BotCommand statusCmd(String orderId) {
        return new BotCommand("escrow", List.of("status", orderId));
    }

    private static BotCommand cancelCmd(String orderId) {
        return new BotCommand("escrow", List.of("cancel", orderId));
    }

    /** 用真实 gate + 真实 service + 真实 registry 装配 handler。ctx 决定门禁看到的事实。 */
    private static TradeCommandHandler handler(int maxConcurrent, Duration cooldown,
                                               TradeAdmissionContext ctx) {
        TradeAdmissionGate gate = new TradeAdmissionGate(
                new TradeAdmissionPolicy(maxConcurrent, cooldown));
        TradeHistoryPort history = id -> new TradeAdmissionContext(
                id, ctx.activeTradeCount(), ctx.lastCompletedTradeAt(), ctx.hasUnresolvedDispute());
        Clock clock = Clock.fixed(T0, ZoneOffset.UTC);
        InMemoryStore store = new InMemoryStore();
        return new TradeCommandHandler(
                new EscrowTradeService(gate, history, store, clock),
                new AmountTierPolicy(new BigDecimal("100"), new BigDecimal("1000")),
                new PendingTradeRegistry(PENDING_TTL, clock),
                store);
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
    @DisplayName("create 为预览：返回风险提示（ET-34 创建前强制展示），不落单")
    void createPreviewsWithRiskPrompt() {
        TradeCommandHandler h = handler(1, Duration.ZERO, clean());

        String out = h.handle(createCmd("2002", "100", "USDT"), ACTOR);

        assertThat(out).contains("确认").contains("100");   // 风险提示（说人话）
        assertThat(out).doesNotContain("已创建订单");         // 预览不落单
    }

    @Test
    @DisplayName("确认后落单：先 create 预览、再 confirm → 回执含订单号")
    void createsOrderAfterPreview() {
        TradeCommandHandler h = handler(1, Duration.ZERO, clean());

        h.handle(createCmd("2002", "100", "USDT"), ACTOR);   // ET-34 必经的预览

        assertThat(h.handle(confirmCmd("2002", "100", "USDT"), ACTOR)).isEqualTo("已创建订单 #1");
    }

    @Test
    @DisplayName("ET-34 不可绕过：未经 create 直接 confirm → 被拒，且不落单")
    void confirmWithoutPreviewRejected() {
        TradeCommandHandler h = handler(5, Duration.ZERO, clean());

        String out = h.handle(confirmCmd("2002", "100", "USDT"), ACTOR);

        assertThat(out).contains("先预览").doesNotContain("已创建订单");
    }

    @Test
    @DisplayName("ET-34 不可绕过：预览后改参数再 confirm → 被拒（提示针对的是原参数）")
    void confirmWithChangedParamsRejected() {
        TradeCommandHandler h = handler(5, Duration.ZERO, clean());
        h.handle(createCmd("2002", "100", "USDT"), ACTOR);

        String out = h.handle(confirmCmd("2002", "999", "USDT"), ACTOR);

        assertThat(out).contains("先预览").doesNotContain("已创建订单");
    }

    @Test
    @DisplayName("并发超限 → 被拒 + 原因，且不落单")
    void rejectedByConcurrentLimit() {
        TradeCommandHandler h = handler(1, Duration.ZERO,
                new TradeAdmissionContext(BUYER, 1, null, false));
        h.handle(createCmd("2002", "100", "USDT"), ACTOR);

        String out = h.handle(confirmCmd("2002", "100", "USDT"), ACTOR);

        assertThat(out).contains("被拒").contains("已有进行中的交易");
    }

    @Test
    @DisplayName("冷却期 → 被拒 + 含可重试时刻")
    void rejectedByCooldown() {
        TradeCommandHandler h = handler(5, Duration.ofHours(24),
                new TradeAdmissionContext(BUYER, 0, T0.minus(Duration.ofHours(1)), false));
        h.handle(createCmd("2002", "100", "USDT"), ACTOR);

        String out = h.handle(confirmCmd("2002", "100", "USDT"), ACTOR);

        assertThat(out).contains("被拒").contains("冷却期未满")
                .contains("可重试：").doesNotContain("暂无法预估");
    }

    @Test
    @DisplayName("争议冻结 → 被拒 + 可重试为暂无法预估")
    void rejectedByDispute() {
        TradeCommandHandler h = handler(5, Duration.ZERO,
                new TradeAdmissionContext(BUYER, 0, null, true));
        h.handle(createCmd("2002", "100", "USDT"), ACTOR);

        String out = h.handle(confirmCmd("2002", "100", "USDT"), ACTOR);

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
    @DisplayName("create 非正金额 → 友好回执，不冒未捕获异常（异常防护对称）")
    void createRejectsNonPositiveAmount() {
        TradeCommandHandler h = handler(5, Duration.ZERO, clean());

        assertThat(h.handle(createCmd("2002", "-5", "USDT"), ACTOR)).contains("无法创建");
        assertThat(h.handle(createCmd("2002", "0", "USDT"), ACTOR)).contains("无法创建");
    }

    @Test
    @DisplayName("自交易（卖方=买方）→ 无法创建（create 预览时即拦下）")
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

    @Test
    @DisplayName("T2 状态查询：命中订单 → 回订单号 + 状态摘要 + 下一步")
    void statusReturnsView() {
        TradeCommandHandler h = handler(5, Duration.ZERO, clean());
        h.handle(createCmd("2002", "100", "USDT"), ACTOR);
        h.handle(confirmCmd("2002", "100", "USDT"), ACTOR);   // 落单 #1（OPEN）

        String out = h.handle(statusCmd("1"), ACTOR);

        assertThat(out).contains("#1").contains("订单已创建").contains("卖方确认接单");
    }

    @Test
    @DisplayName("T2 状态查询：订单不存在 → 明说找不到（不报错、不空响应）")
    void statusUnknownOrder() {
        TradeCommandHandler h = handler(5, Duration.ZERO, clean());

        String out = h.handle(statusCmd("999"), ACTOR);

        assertThat(out).contains("不存在");
    }

    @Test
    @DisplayName("T2 状态查询：订单号缺失或非数字 → 用法说明")
    void statusBadArgs() {
        TradeCommandHandler h = handler(5, Duration.ZERO, clean());

        assertThat(h.handle(new BotCommand("escrow", List.of("status")), ACTOR)).contains("用法");
        assertThat(h.handle(statusCmd("abc"), ACTOR)).contains("用法");
    }

    @Test
    @DisplayName("cancel：当事人取消未推进的订单 → 回已取消，且状态确实变了")
    void cancelOwnOrder() {
        TradeCommandHandler h = handler(5, Duration.ZERO, clean());
        h.handle(createCmd("2002", "100", "USDT"), ACTOR);
        h.handle(confirmCmd("2002", "100", "USDT"), ACTOR);   // 落单 #1（OPEN）

        String out = h.handle(cancelCmd("1"), ACTOR);

        assertThat(out).contains("已取消");
        // 关键：不是只有回执变了——状态真落库（否则回执就是谎报）
        assertThat(h.handle(statusCmd("1"), ACTOR)).contains("已取消");
    }

    @Test
    @DisplayName("cancel：非当事人 → 拒绝（防越权取消他人订单），且状态不变")
    void cancelOthersOrderRejected() {
        TradeCommandHandler h = handler(5, Duration.ZERO, clean());
        h.handle(createCmd("2002", "100", "USDT"), ACTOR);
        h.handle(confirmCmd("2002", "100", "USDT"), ACTOR);
        CommandActor stranger = new CommandActor(9999L, MemberRole.MEMBER);

        String out = h.handle(cancelCmd("1"), stranger);

        assertThat(out).contains("无权");
        assertThat(h.handle(statusCmd("1"), ACTOR)).doesNotContain("已取消");
    }

    @Test
    @DisplayName("cancel：订单不存在 → 明说找不到")
    void cancelUnknownOrder() {
        TradeCommandHandler h = handler(5, Duration.ZERO, clean());

        assertThat(h.handle(cancelCmd("999"), ACTOR)).contains("不存在");
    }
}
