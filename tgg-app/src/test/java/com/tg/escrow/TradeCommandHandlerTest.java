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
import com.tg.escrow.core.NoticePolicy;
import com.tg.escrow.escrow.AmountTierPolicy;
import com.tg.escrow.escrow.ConcurrentOrderUpdateException;
import com.tg.escrow.escrow.EscrowOrder;
import com.tg.escrow.escrow.EscrowOrderLookupPort;
import com.tg.escrow.escrow.EscrowOrderStore;
import com.tg.escrow.escrow.EscrowTradeService;
import com.tg.escrow.escrow.PendingTradeRegistry;
import com.tg.escrow.escrow.TradeAdmissionContext;
import com.tg.escrow.escrow.TradeAdmissionGate;
import com.tg.escrow.escrow.TradeAdmissionPolicy;
import com.tg.escrow.escrow.TradeHistoryPort;
import com.tg.escrow.escrow.TradeInvite;
import com.tg.escrow.escrow.TradeInviteService;
import com.tg.escrow.escrow.TradeInviteStore;
import com.tg.escrow.escrow.TradeMaintenanceService;
import com.tg.escrow.escrow.TradeReviewService;
import com.tg.escrow.escrow.TradeReviewStore;
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
        private boolean conflictOnNextSave;

        /** 让下一次 save 抛并发冲突（模拟乐观锁失败，无需真实并发）。 */
        void failNextSaveWithConflict() {
            conflictOnNextSave = true;
        }

        @Override
        public EscrowOrder save(EscrowOrder order) {
            if (conflictOnNextSave) {
                conflictOnNextSave = false;
                throw new ConcurrentOrderUpdateException("订单已被他人变更（并发写入冲突），请重查后重试");
            }
            order.assignId(++seq);
            byId.put(order.getId(), order);
            return order;
        }

        @Override
        public java.util.Optional<EscrowOrder> byId(long orderId) {
            return java.util.Optional.ofNullable(byId.get(orderId));
        }
    }

    /** 本 bot 用户名（用于拼深链）。 */
    private static final String BOT_USERNAME = "nq9831ops_bot";

    /** 内存邀请存储（建邀请/接单路径用）。 */
    private static final class InMemoryInviteStore implements TradeInviteStore {
        private final java.util.Map<String, TradeInvite> byToken = new java.util.HashMap<>();
        private long seq;

        @Override
        public TradeInvite save(TradeInvite invite) {
            if (invite.getId() == null) {
                invite.assignId(++seq);
            }
            byToken.put(invite.getToken(), invite);
            return invite;
        }

        @Override
        public java.util.Optional<TradeInvite> byToken(String token) {
            return java.util.Optional.ofNullable(byToken.get(token));
        }
    }

    /** 内存评价存储（重复评价抛异常，模拟库层唯一索引）。 */
    private static final class InMemoryReviewStore implements TradeReviewStore {
        private final java.util.Map<Long, java.util.Set<Long>> byOrder = new java.util.HashMap<>();

        @Override
        public void record(long orderId, long reviewerId, int score, java.time.Instant createdAt) {
            java.util.Set<Long> reviewers = byOrder.computeIfAbsent(orderId, k -> new java.util.HashSet<>());
            if (!reviewers.add(reviewerId)) {
                throw new TggException("交易评价：该方已评价过本单，不可重复评价");
            }
        }

        @Override
        public java.util.Set<Long> reviewersOf(long orderId) {
            return java.util.Set.copyOf(byOrder.getOrDefault(orderId, java.util.Set.of()));
        }
    }

    /**
     * 记录主动通知：{@code chatId|silent|loud|text}；{@code failNextSend} 为一次性开关
     * （置位后只失败一次），便于断言"发不出去时回执如何表现"。
     */
    private static final class RecordingNotifications implements BotReplyPort {
        final java.util.List<String> sent = new java.util.ArrayList<>();
        boolean failNextSend;

        @Override
        public void sendText(long chatId, String text) {
            sent.add(chatId + "|loud|" + text);
        }

        @Override
        public void sendText(long chatId, String text, NoticePolicy policy) {
            if (failNextSend) {
                failNextSend = false;
                throw new TggException("模拟发送失败（对方未与机器人会话 / 被拉黑）");
            }
            sent.add(chatId + "|" + (policy.silent() ? "silent" : "loud") + "|" + text);
        }

        @Override
        public void sendTextWithWebApp(long chatId, String text, String buttonText, String url) {
            throw new UnsupportedOperationException("本类不测按钮消息");
        }

        @Override
        public void ackCallback(String callbackQueryId) {
            throw new UnsupportedOperationException("本类不测 callback 应答");
        }
    }

    /** 通知器：真实 TradeNotifier + 记录型出口（不 mock 中间层）。 */
    private static TradeNotifier notifier(RecordingNotifications notifications) {
        return new TradeNotifier(notifications, Clock.fixed(T0, ZoneOffset.UTC), null);
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

    /** 装配结果：handler + 其内部 store（并发冲突测试需要操纵 store）+ 通知记录（通知断言需要）。 */
    private record Wiring(TradeCommandHandler handler, InMemoryStore store,
                          RecordingNotifications notifications) {
    }

    private static Wiring wiring(int maxConcurrent, Duration cooldown, TradeAdmissionContext ctx) {
        TradeAdmissionGate gate = new TradeAdmissionGate(
                new TradeAdmissionPolicy(maxConcurrent, cooldown));
        TradeHistoryPort history = id -> new TradeAdmissionContext(
                id, ctx.activeTradeCount(), ctx.lastCompletedTradeAt(), ctx.hasUnresolvedDispute());
        Clock clock = Clock.fixed(T0, ZoneOffset.UTC);
        InMemoryStore store = new InMemoryStore();
        RecordingNotifications notifications = new RecordingNotifications();
        return new Wiring(new TradeCommandHandler(
                new EscrowTradeService(gate, history, store, clock),
                new AmountTierPolicy(new BigDecimal("100"), new BigDecimal("1000")),
                new PendingTradeRegistry(PENDING_TTL, clock),
                store,
                inviteService(gate, history, store, clock),
                new InviteLink(BOT_USERNAME),
                new TradeReviewService(new InMemoryReviewStore(), clock),
                maintenanceService(clock),
                notifier(notifications)), store, notifications);
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
                store,
                inviteService(gate, history, store, clock),
                new InviteLink(BOT_USERNAME),
                new TradeReviewService(new InMemoryReviewStore(), clock),
                maintenanceService(clock),
                notifier(new RecordingNotifications()));
    }

    /** 维护期服务：5 选项 + 默认第 3 项（24h）；超时规则与维护期时长同源。 */
    private static TradeMaintenanceService maintenanceService(Clock clock) {
        var window = new com.tg.escrow.escrow.MaintenanceWindow(
                java.util.List.of(java.time.Duration.ofHours(1), java.time.Duration.ofHours(6),
                        java.time.Duration.ofHours(24), java.time.Duration.ofHours(72),
                        java.time.Duration.ofHours(168)), 2);
        var policy = new com.tg.escrow.escrow.TradeTimeoutPolicy(java.util.Map.of(
                EscrowOrder.State.DELIVERED,
                new com.tg.escrow.escrow.TradeTimeoutPolicy.TimeoutRule(
                        window.defaultDuration(),
                        com.tg.escrow.escrow.TradeTimeoutPolicy.TimeoutAction.AUTO_CONFIRM)));
        return new TradeMaintenanceService(window, policy, clock);
    }

    /** 邀请服务：复用同一门禁/历史/存储/时钟，令牌固定便于断言。 */
    private static TradeInviteService inviteService(TradeAdmissionGate gate, TradeHistoryPort history,
                                                   EscrowOrderStore store, Clock clock) {
        return new TradeInviteService(gate, history, new InMemoryInviteStore(), store,
                Duration.ofHours(24), () -> "invitetoken0", clock);
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

        assertThat(out).contains("#1").contains("订单已创建").contains("买方托管资金");
        assertThat(out)
                .as("OPEN 的提示不得指向做不到的命令：命令层没有『卖方接单』这条路"
                        + "（/escrow confirm 是买方预览风险后的第二步，不是卖方动作）")
                .doesNotContain("/escrow confirm");
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

    @Test
    @DisplayName("cancel 并发冲突：订单已被他人变更 → 提示重查，且不谎报已取消")
    void cancelReportsConcurrentConflict() {
        Wiring w = wiring(5, Duration.ZERO, clean());
        w.handler().handle(createCmd("2002", "100", "USDT"), ACTOR);
        w.handler().handle(confirmCmd("2002", "100", "USDT"), ACTOR);
        w.store().failNextSaveWithConflict();   // 模拟：读之后订单被别人改过

        String out = w.handler().handle(cancelCmd("1"), ACTOR);

        assertThat(out).contains("已被他人变更").contains("重查");
        assertThat(out).doesNotContain("已取消");   // 冲突时绝不能报成功
    }

    // ── invite：深链邀请（Wave 3）────────────────────────────────────────

    private static BotCommand inviteCmd(String amount, String currency) {
        return new BotCommand("escrow", List.of("invite", amount, currency));
    }

    @Test
    @DisplayName("invite：生成待接受邀请，回执含 startapp 深链，且【不落单】")
    void inviteReturnsDeepLink() {
        TradeCommandHandler h = handler(5, Duration.ZERO, clean());

        String out = h.handle(inviteCmd("100", "USDT"), ACTOR);

        assertThat(out).contains("https://t.me/" + BOT_USERNAME + "?startapp=");
        assertThat(out).contains("100").contains("USDT");
        assertThat(out).doesNotContain("已创建订单");   // 建邀请阶段不物化订单
    }

    @Test
    @DisplayName("invite：参数缺失 → 用法说明")
    void inviteBadArgs() {
        TradeCommandHandler h = handler(5, Duration.ZERO, clean());

        assertThat(h.handle(new BotCommand("escrow", List.of("invite")), ACTOR)).contains("用法");
        assertThat(h.handle(new BotCommand("escrow", List.of("invite", "100")), ACTOR)).contains("用法");
        assertThat(h.handle(new BotCommand("escrow", List.of("invite", "100", "")), ACTOR)).contains("用法");
    }

    @Test
    @DisplayName("invite：白名单外币种 → 无法创建邀请（与 create 同一收口）")
    void inviteRejectsUnsupportedCurrency() {
        TradeCommandHandler h = handler(5, Duration.ZERO, clean());

        assertThat(h.handle(inviteCmd("100", "BTC"), ACTOR)).contains("无法创建邀请");
    }

    @Test
    @DisplayName("invite：发起人已达并发上限 → 被拒 + 原因")
    void inviteRejectedByGate() {
        TradeCommandHandler h = handler(1, Duration.ZERO,
                new TradeAdmissionContext(BUYER, 1, null, false));

        String out = h.handle(inviteCmd("100", "USDT"), ACTOR);

        assertThat(out).contains("被拒").contains("已有进行中的交易");
    }

    @Test
    @DisplayName("用法说明涵盖 invite（用户能从回执知道怎么生成邀请）")
    void usageMentionsInvite() {
        TradeCommandHandler h = handler(5, Duration.ZERO, clean());

        assertThat(h.handle(new BotCommand("escrow", List.of("invite")), ACTOR)).contains("invite");
    }

    // ── 生命周期命令（Wave 2 接线）────────────────────────────────────────

    private static final long SELLER = 2002L;
    private static final CommandActor SELLER_ACTOR = new CommandActor(SELLER, MemberRole.MEMBER);

    private static BotCommand lockCmd(String orderId) {
        return new BotCommand("escrow", List.of("lock", orderId));
    }

    private static BotCommand deliverCmd(String orderId) {
        return new BotCommand("escrow", List.of("deliver", orderId));
    }

    private static BotCommand releaseCmd(String orderId) {
        return new BotCommand("escrow", List.of("release", orderId));
    }

    private static BotCommand refundCmd(String orderId) {
        return new BotCommand("escrow", List.of("refund", orderId, "协商退款"));
    }

    private static BotCommand disputeCmd(String orderId) {
        return new BotCommand("escrow", List.of("dispute", orderId, "未收到货"));
    }

    /** 落一笔 OPEN 订单（走真实 create → confirm 两步流），返回订单号 1。 */
    private static void placeOpenOrder(TradeCommandHandler h) {
        h.handle(createCmd("2002", "100", "USDT"), ACTOR);
        h.handle(confirmCmd("2002", "100", "USDT"), ACTOR);
    }

    @Test
    @DisplayName("lock：买方托管登记 → 状态真变，且回执显式声明「链上未接入」")
    void lockAdvancesAndDeclaresChainGap() {
        TradeCommandHandler h = handler(5, Duration.ZERO, clean());
        placeOpenOrder(h);

        String out = h.handle(lockCmd("1"), ACTOR);

        assertThat(out).contains("已锁仓");
        assertThat(out)
                .as("资金类回执必须声明链上未接入——否则等于把状态登记谎报成真实资金动作")
                .contains("链上");
        assertThat(h.handle(statusCmd("1"), ACTOR)).contains("资金已托管");
    }

    @Test
    @DisplayName("lock：第三方发命令 → 无权，且状态不变")
    void lockByStrangerRejected() {
        TradeCommandHandler h = handler(5, Duration.ZERO, clean());
        placeOpenOrder(h);

        String out = h.handle(lockCmd("1"), SELLER_ACTOR);   // 卖方不是买方

        assertThat(out).contains("无权");
        assertThat(h.handle(statusCmd("1"), ACTOR)).contains("等待买方托管资金");
    }

    @Test
    @DisplayName("deliver：卖方交付 → 推进到已交付")
    void deliverAdvances() {
        TradeCommandHandler h = handler(5, Duration.ZERO, clean());
        placeOpenOrder(h);
        h.handle(lockCmd("1"), ACTOR);

        String out = h.handle(deliverCmd("1"), SELLER_ACTOR);

        assertThat(out).contains("已交付");
        assertThat(h.handle(statusCmd("1"), ACTOR)).contains("卖方已交付");
    }

    @Test
    @DisplayName("release：买方验收放款 → 终态，且回执声明「链上未接入」")
    void releaseAdvancesAndDeclaresChainGap() {
        TradeCommandHandler h = handler(5, Duration.ZERO, clean());
        placeOpenOrder(h);
        h.handle(lockCmd("1"), ACTOR);
        h.handle(deliverCmd("1"), SELLER_ACTOR);

        String out = h.handle(releaseCmd("1"), ACTOR);

        assertThat(out).contains("已放款").contains("链上");
        assertThat(h.handle(statusCmd("1"), ACTOR)).contains("已放款给卖方");
    }

    @Test
    @DisplayName("refund：任一方可发起协商退款 → 终态，且回执声明「链上未接入」")
    void refundAdvancesAndDeclaresChainGap() {
        TradeCommandHandler h = handler(5, Duration.ZERO, clean());
        placeOpenOrder(h);
        h.handle(lockCmd("1"), ACTOR);

        String out = h.handle(refundCmd("1"), SELLER_ACTOR);

        assertThat(out).contains("已退款").contains("链上");
        assertThat(h.handle(statusCmd("1"), ACTOR)).contains("已退款给买方");
    }

    @Test
    @DisplayName("dispute：锁仓后可发起争议 → 争议态且记录理由")
    void disputeAdvances() {
        TradeCommandHandler h = handler(5, Duration.ZERO, clean());
        placeOpenOrder(h);
        h.handle(lockCmd("1"), ACTOR);

        String out = h.handle(disputeCmd("1"), SELLER_ACTOR);

        assertThat(out).contains("争议");
        assertThat(h.handle(statusCmd("1"), ACTOR)).contains("订单争议中");
    }

    @Test
    @DisplayName("生命周期命令：订单号缺失/非数字 → 用法说明")
    void lifecycleBadArgsReturnUsage() {
        TradeCommandHandler h = handler(5, Duration.ZERO, clean());

        assertThat(h.handle(new BotCommand("escrow", List.of("lock")), ACTOR)).contains("用法");
        assertThat(h.handle(lockCmd("abc"), ACTOR)).contains("用法");
        assertThat(h.handle(new BotCommand("escrow", List.of("dispute", "1")), ACTOR)).contains("用法");
    }

    @Test
    @DisplayName("生命周期命令：订单不存在 → 明说找不到（不报错、不空响应）")
    void lifecycleUnknownOrder() {
        TradeCommandHandler h = handler(5, Duration.ZERO, clean());

        assertThat(h.handle(lockCmd("999"), ACTOR)).contains("不存在");
        assertThat(h.handle(deliverCmd("999"), SELLER_ACTOR)).contains("不存在");
    }

    @Test
    @DisplayName("状态回执给出【真实可用】的下一步命令（不指挥用户发不存在的命令）")
    void statusSuggestsRealCommand() {
        TradeCommandHandler h = handler(5, Duration.ZERO, clean());
        placeOpenOrder(h);
        h.handle(lockCmd("1"), ACTOR);

        String out = h.handle(statusCmd("1"), ACTOR);

        assertThat(out).contains("/escrow deliver 1");
    }

    // ── 评价命令（Wave 3 接线）──────────────────────────────────────────────

    private static BotCommand reviewCmd(String orderId, String score) {
        return new BotCommand("escrow", List.of("review", orderId, score));
    }

    @Test
    @DisplayName("review：终态交易可评价；同一方重复评价 → 拒")
    void reviewRecordsOnce() {
        TradeCommandHandler h = handler(5, Duration.ZERO, clean());
        placeOpenOrder(h);
        h.handle(lockCmd("1"), ACTOR);
        h.handle(deliverCmd("1"), SELLER_ACTOR);
        h.handle(releaseCmd("1"), ACTOR);           // 终态 RELEASED

        assertThat(h.handle(reviewCmd("1", "5"), ACTOR)).contains("评价");
        assertThat(h.handle(reviewCmd("1", "1"), ACTOR))
                .as("同一方不得重复评价（守卫 + 唯一索引两道防线）")
                .contains("无法评价");
    }

    @Test
    @DisplayName("review：未到终态 → 拒；参数缺失/非法 → 用法说明")
    void reviewGuardsAndArgs() {
        TradeCommandHandler h = handler(5, Duration.ZERO, clean());
        placeOpenOrder(h);

        assertThat(h.handle(reviewCmd("1", "5"), ACTOR)).contains("无法评价");
        assertThat(h.handle(new BotCommand("escrow", List.of("review", "1")), ACTOR)).contains("用法");
        assertThat(h.handle(reviewCmd("abc", "5"), ACTOR)).contains("用法");
        assertThat(h.handle(reviewCmd("1", "x"), ACTOR)).contains("用法");
    }

    @Test
    @DisplayName("终态订单的状态回执提示可评价（命令真实存在，不指挥用户发空命令）")
    void statusSuggestsReviewOnTerminal() {
        TradeCommandHandler h = handler(5, Duration.ZERO, clean());
        placeOpenOrder(h);
        h.handle(lockCmd("1"), ACTOR);
        h.handle(releaseCmd("1"), ACTOR);

        assertThat(h.handle(statusCmd("1"), ACTOR)).contains("/escrow review 1");
    }

    // ── 接线集成（Wave 4）：整链路走通，真实依赖非 mock ────────────────────

    @Test
    @DisplayName("【整链路】create→confirm→lock→deliver→release→review 全程可走通")
    void fullHappyPathThroughRealDependencies() {
        TradeCommandHandler h = handler(5, Duration.ZERO, clean());
        placeOpenOrder(h);

        assertThat(h.handle(lockCmd("1"), ACTOR)).contains("已锁仓");
        assertThat(h.handle(deliverCmd("1"), SELLER_ACTOR)).contains("已交付");
        assertThat(h.handle(releaseCmd("1"), ACTOR)).contains("放款给卖方");
        assertThat(h.handle(reviewCmd("1", "5"), ACTOR)).contains("评价");

        // 终态经状态回执核对（不是只看某一步的回执）
        assertThat(h.handle(statusCmd("1"), ACTOR)).contains("已放款给卖方");
    }

    @Test
    @DisplayName("【整链路】create→confirm→lock→dispute 争议可发起并可查")
    void disputePathThroughRealDependencies() {
        TradeCommandHandler h = handler(5, Duration.ZERO, clean());
        placeOpenOrder(h);

        assertThat(h.handle(lockCmd("1"), ACTOR)).contains("已锁仓");
        assertThat(h.handle(disputeCmd("1"), SELLER_ACTOR)).contains("争议");
        assertThat(h.handle(statusCmd("1"), ACTOR)).contains("订单争议中");
    }

    @Test
    @DisplayName("交付后状态回执报维护期剩余，并明说【不会自动执行】（无调度器，不误导用户）")
    void statusReportsMaintenanceWindow() {
        TradeCommandHandler h = handler(5, Duration.ZERO, clean());
        placeOpenOrder(h);
        h.handle(lockCmd("1"), ACTOR);
        h.handle(deliverCmd("1"), SELLER_ACTOR);

        String out = h.handle(statusCmd("1"), ACTOR);

        assertThat(out).contains("维护期");
        assertThat(out)
                .as("维护期超时不会自动放款——必须如实告知，别让用户以为到点钱会自己走")
                .contains("不会自动执行");
    }

    // ── 对手方通知接线（Wave 3）：整链路真实依赖，不 mock 中间层 ──────────────

    @Test
    @DisplayName("【整链路】买方 lock 后卖方收到主动通知——不是只回了发起方")
    void lockNotifiesCounterparty() {
        Wiring w = wiring(5, Duration.ZERO, clean());
        w.handler().handle(createCmd("2002", "100", "USDT"), ACTOR);
        w.handler().handle(confirmCmd("2002", "100", "USDT"), ACTOR);
        w.notifications().sent.clear();

        w.handler().handle(lockCmd("1"), ACTOR);

        assertThat(w.notifications().sent)
                .as("卖方应收到通知（chatId = 卖方）")
                .anySatisfy(s -> assertThat(s).startsWith(SELLER + "|"));
    }

    @Test
    @DisplayName("通知发不出去 → 回执仍报成功，且追加「对方可能收不到」（不静默失败）")
    void notificationFailureStillReportsSuccessWithHint() {
        Wiring w = wiring(5, Duration.ZERO, clean());
        w.handler().handle(createCmd("2002", "100", "USDT"), ACTOR);
        w.handler().handle(confirmCmd("2002", "100", "USDT"), ACTOR);
        w.notifications().failNextSend = true;   // 下一次（lock 的通知）失败

        String out = w.handler().handle(lockCmd("1"), ACTOR);

        assertThat(out).as("迁移已提交，回执必须报成功").contains("已锁仓");
        assertThat(out).as("但要告诉发起方对方可能收不到，不能静默失败").contains("收不到");
    }

    @Test
    @DisplayName("通知成功 → 回执不追加提示（不误报「对方收不到」）")
    void successfulNotificationLeavesNoHint() {
        Wiring w = wiring(5, Duration.ZERO, clean());
        w.handler().handle(createCmd("2002", "100", "USDT"), ACTOR);
        w.handler().handle(confirmCmd("2002", "100", "USDT"), ACTOR);

        String out = w.handler().handle(lockCmd("1"), ACTOR);

        assertThat(out).contains("已锁仓").doesNotContain("收不到");
    }
}
