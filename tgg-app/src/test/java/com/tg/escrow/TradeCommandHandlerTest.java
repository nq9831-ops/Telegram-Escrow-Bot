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

import com.tg.escrow.chain.ChainGateway;
import com.tg.escrow.chain.ChainSettings;
import com.tg.escrow.chain.UpgradeStatus;
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
import com.tg.escrow.escrow.AllowedCurrencies;
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
import com.tg.escrow.core.UserIdMasker;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
    /** 日志脱敏测试盐（≥ UserIdMasker.MIN_SALT_LENGTH）。 */
    private static final String TEST_SALT = "unit-test-salt-0123456789";
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
            // 与生产 JpaEscrowOrderStore 同语义：只对新建订单回填 ID，已落库订单的 ID 不变——
            // 每次 save 都重赋 ID 会让同一订单在不同命令里换号（绑定/结算按号对不上）。
            if (order.getId() == null) {
                order.assignId(++seq);
            }
            byId.put(order.getId(), order);
            return order;
        }

        @Override
        public java.util.Optional<EscrowOrder> byId(long orderId) {
            return java.util.Optional.ofNullable(byId.get(orderId));
        }

        @Override
        public java.util.List<EscrowOrder> recentFor(long userId, int limit) {
            // 与 JpaEscrowOrderLookup 同语义：买卖任一侧、按最后更新倒序、limit 夹取到 ≥1
            return byId.values().stream()
                    .filter(o -> o.getBuyerUserId() == userId || o.getSellerUserId() == userId)
                    .sorted(java.util.Comparator.comparing(EscrowOrder::getUpdatedAt).reversed())
                    .limit(Math.max(limit, 1))
                    .toList();
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
        public void sendTextWithButtons(long chatId, String text,
                                        java.util.List<BotReply.ActionButton> buttons) {
            // 本替身只验"通知有没有发出"；按钮路径按文本处理（不静默丢弃通知）
            sendText(chatId, text);
        }

        @Override
        public void sendTextWithButtons(long chatId, String text,
                                        java.util.List<BotReply.ActionButton> buttons,
                                        com.tg.escrow.core.NoticePolicy policy) {
            // 与 sendText(带策略) 同源记账——否则通知改走按钮路径后，failNextSend 开关会静默失效，
            // "发不出去时回执如何表现"的用例就变成永远通过的空洞断言
            sendText(chatId, text, policy);
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
        return new TradeNotifier(notifications, Clock.fixed(T0, ZoneOffset.UTC), null,
                new com.tg.escrow.core.UserIdMasker("test-salt"));
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
                          RecordingNotifications notifications,
                          StubTradeGroup.RecordingPort groupPort,
                          StubTradeGroup.MapStore groupStore) {
    }

    private static Wiring wiring(int maxConcurrent, Duration cooldown, TradeAdmissionContext ctx) {
        TradeAdmissionGate gate = new TradeAdmissionGate(
                new TradeAdmissionPolicy(maxConcurrent, cooldown));
        TradeHistoryPort history = id -> new TradeAdmissionContext(
                id, ctx.activeTradeCount(), ctx.lastCompletedTradeAt(), ctx.hasUnresolvedDispute());
        Clock clock = Clock.fixed(T0, ZoneOffset.UTC);
        InMemoryStore store = new InMemoryStore();
        RecordingNotifications notifications = new RecordingNotifications();
        StubTradeGroup.MapStore groupStore = StubTradeGroup.mapStore();
        StubTradeGroup.RecordingPort groupPort = StubTradeGroup.port();
        return new Wiring(new TradeCommandHandler(
                new EscrowTradeService(gate, history, store, AllowedCurrencies.all(), clock),
                new AmountTierPolicy(new BigDecimal("100"), new BigDecimal("1000")),
                new PendingTradeRegistry(PENDING_TTL, clock),
                store,
                inviteService(gate, history, store, clock),
                new InviteLink(BOT_USERNAME),
                new TradeReviewService(new InMemoryReviewStore(), clock),
                maintenanceService(clock),
                notifier(notifications), StubTraderStats.EMPTY,
                new com.tg.escrow.escrow.TraderCreditService(),
                AllowedCurrencies.all(), new UserIdMasker(TEST_SALT),
                StubTradeGroup.service(groupStore, clock), groupPort, com.tg.escrow.escrow.FeePolicy.zero(), StubFraudLinkage.service(), StubDispute.service(clock),
                new com.tg.escrow.escrow.ConfirmGate(Duration.ofMinutes(5), clock)), store, notifications,
                groupPort, groupStore);
    }

    /** 用真实 gate + 真实 service + 真实 registry 装配 handler。ctx 决定门禁看到的事实。 */
    private static TradeCommandHandler handler(int maxConcurrent, Duration cooldown,
                                               TradeAdmissionContext ctx) {
        return handler(maxConcurrent, cooldown, ctx, AllowedCurrencies.all(), new UserIdMasker(TEST_SALT));
    }

    /** 变体：允许集与脱敏器可指定（③ 币种收窄 / ④ 日志脱敏断言需要）。 */
    private static TradeCommandHandler handler(int maxConcurrent, Duration cooldown,
                                               TradeAdmissionContext ctx,
                                               AllowedCurrencies currencies, UserIdMasker masker) {
        return handler(maxConcurrent, cooldown, ctx, currencies, masker,
                com.tg.escrow.escrow.FeePolicy.zero(), StubTraderStats.EMPTY);
    }

    /** 变体：费率策略与交易者统计可指定（ET-35 首单豁免用例需要非 0 费率与带成交史的卖方统计）。 */
    private static TradeCommandHandler handler(int maxConcurrent, Duration cooldown,
                                               TradeAdmissionContext ctx,
                                               AllowedCurrencies currencies, UserIdMasker masker,
                                               com.tg.escrow.escrow.FeePolicy feePolicy,
                                               com.tg.escrow.escrow.TraderStatsPort stats) {
        return handler(maxConcurrent, cooldown, ctx, currencies, masker, feePolicy, stats, null);
    }

    /** ET-80：再叠加榜单公开偏好（{@code prefs=null} 走恒匿名兼容路径）。 */
    private static TradeCommandHandler handler(int maxConcurrent, Duration cooldown,
                                               TradeAdmissionContext ctx,
                                               AllowedCurrencies currencies, UserIdMasker masker,
                                               com.tg.escrow.escrow.FeePolicy feePolicy,
                                               com.tg.escrow.escrow.TraderStatsPort stats,
                                               com.tg.escrow.moderation.UserPreferencePort prefs) {
        return handler(maxConcurrent, cooldown, ctx, currencies, masker, feePolicy, stats,
                prefs, null);
    }

    /** S5 存证：再叠加链上存证腿（{@code sink=null} 走"未接线"路径——如实报「未成功」）。 */
    private static TradeCommandHandler handler(int maxConcurrent, Duration cooldown,
                                               TradeAdmissionContext ctx,
                                               AllowedCurrencies currencies, UserIdMasker masker,
                                               com.tg.escrow.escrow.FeePolicy feePolicy,
                                               com.tg.escrow.escrow.TraderStatsPort stats,
                                               com.tg.escrow.moderation.UserPreferencePort prefs,
                                               ChainEvidenceSink sink) {
        TradeAdmissionGate gate = new TradeAdmissionGate(
                new TradeAdmissionPolicy(maxConcurrent, cooldown));
        TradeHistoryPort history = id -> new TradeAdmissionContext(
                id, ctx.activeTradeCount(), ctx.lastCompletedTradeAt(), ctx.hasUnresolvedDispute());
        Clock clock = Clock.fixed(T0, ZoneOffset.UTC);
        InMemoryStore store = new InMemoryStore();
        // null 偏好 → 本地恒匿名实现（与兼容构造器语义一致），避免规范构造器的非空校验。
        com.tg.escrow.moderation.UserPreferencePort effectivePrefs =
                prefs != null ? prefs : new com.tg.escrow.moderation.UserPreferencePort() {
                    @Override public String noticeModeOf(long userId) {
                        return "all";
                    }
                    @Override public void setNoticeMode(long userId, String mode) {
                    }
                    @Override public RankVisibility rankVisibilityOf(long userId) {
                        return new RankVisibility(false, null);
                    }
                    @Override public void setRankPublic(long userId, boolean p, String name) {
                    }
                };
        return new TradeCommandHandler(
                new EscrowTradeService(gate, history, store, currencies, clock),
                new AmountTierPolicy(new BigDecimal("100"), new BigDecimal("1000")),
                new PendingTradeRegistry(PENDING_TTL, clock),
                store,
                inviteService(gate, history, store, clock),
                new InviteLink(BOT_USERNAME),
                new TradeReviewService(new InMemoryReviewStore(), clock),
                maintenanceService(clock),
                notifier(new RecordingNotifications()), stats,
                new com.tg.escrow.escrow.TraderCreditService(), currencies, masker,
                StubTradeGroup.service(clock), StubTradeGroup.port(), feePolicy,
                StubFraudLinkage.service(), StubDispute.service(clock), effectivePrefs, sink,
                null, new com.tg.escrow.escrow.ConfirmGate(Duration.ofMinutes(5), clock));
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
                Duration.ofHours(24), () -> "invitetoken0", AllowedCurrencies.all(), clock);
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
    @DisplayName("③ create 预览阶段即校验受理币种：不在允许集 → 拒绝且不出风险提示")
    void createPreviewsRejectsCurrencyOutsideAllowedSet() {
        TradeCommandHandler h = handler(5, Duration.ZERO, clean(),
                AllowedCurrencies.fromConfig("USDT"), new UserIdMasker(TEST_SALT));

        String out = h.handle(createCmd("2002", "100", "TON"), ACTOR);

        assertThat(out).startsWith("无法创建：").contains("TON").doesNotContain("风险提示");
    }

    @Test
    @DisplayName("③ 允许集内币种的预览不受影响（既有行为保持）")
    void createPreviewsAllowedCurrencyUnchanged() {
        TradeCommandHandler h = handler(5, Duration.ZERO, clean(),
                AllowedCurrencies.fromConfig("USDT"), new UserIdMasker(TEST_SALT));

        String out = h.handle(createCmd("2002", "100", "USDT"), ACTOR);

        assertThat(out).contains("风险提示");
    }

    @Test
    @DisplayName("④ 命令级 INFO 日志：记子命令与脱敏 actor，不记明文 ID 与参数")
    void commandLogEmitsSubCommandWithMaskedActorOnly() {
        UserIdMasker masker = new UserIdMasker(TEST_SALT);
        TradeCommandHandler h = handler(5, Duration.ZERO, clean(), AllowedCurrencies.all(), masker);
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(TradeCommandHandler.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            h.handle(createCmd("2002", "12345.67", "USDT"), ACTOR);
        } finally {
            logger.detachAppender(appender);
        }
        List<String> lines = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();

        assertThat(lines).anySatisfy(line -> {
            assertThat(line).contains("create").contains(masker.mask(BUYER));
            assertThat(line).doesNotContain(String.valueOf(BUYER))
                    .doesNotContain("2002").doesNotContain("12345.67");
        });
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
        assertThat(out).as("有效期以人可读 UTC 形态展示——ISO 裸串（2026-10-04T05:33:00Z）不是给人读的")
                .contains("UTC");
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

    private static BotCommand releaseYes(String orderId) {
        return new BotCommand("escrow", List.of("release", orderId, "--yes"));
    }

    private static BotCommand refundCmd(String orderId) {
        return new BotCommand("escrow", List.of("refund", orderId, "协商退款"));
    }

    private static BotCommand refundYes(String orderId) {
        return new BotCommand("escrow", List.of("refund", orderId, "协商退款", "--yes"));
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

        h.handle(releaseCmd("1"), ACTOR);
        String out = h.handle(releaseYes("1"), ACTOR);

        assertThat(out).contains("已放款").contains("链上");
        assertThat(h.handle(statusCmd("1"), ACTOR)).contains("已放款给卖方");
    }

    @Test
    @DisplayName("首单豁免（ET-35 · B2）：卖方此前无成功交易 → 放款免平台费，回执披露「首单免平台费」")
    void releaseDisclosesFirstOrderFeeWaiver() {
        TradeCommandHandler h = handler(5, Duration.ZERO, clean(), AllowedCurrencies.all(),
                new UserIdMasker(TEST_SALT), com.tg.escrow.escrow.FeePolicy.of("0.02", "0", "0"),
                StubTraderStats.EMPTY);   // 卖方 2002 无成交史 → 本次为其首笔成功交易
        placeOpenOrder(h);
        h.handle(lockCmd("1"), ACTOR);
        h.handle(deliverCmd("1"), SELLER_ACTOR);

        h.handle(releaseCmd("1"), ACTOR);
        String out = h.handle(releaseYes("1"), ACTOR);

        assertThat(out).contains("首单免平台费");
        assertThat(out)
                .as("首单豁免时卖方实收为全额（100），不扣平台费")
                .contains("卖方实收 100");
    }

    @Test
    @DisplayName("非首单照常收费（ET-35 · B2）：卖方此前已有成功交易 → 回执按基准披露「平台费 X、卖方实收 Y」")
    void releaseChargesNonFirstOrder() {
        var stats = new StubTraderStats(java.util.List.of(new com.tg.escrow.escrow.TraderStats(
                SELLER, 1, 0, 1, 0, 0, null, null, java.util.Map.of())));
        TradeCommandHandler h = handler(5, Duration.ZERO, clean(), AllowedCurrencies.all(),
                new UserIdMasker(TEST_SALT), com.tg.escrow.escrow.FeePolicy.of("0.02", "0", "0"), stats);
        placeOpenOrder(h);
        h.handle(lockCmd("1"), ACTOR);
        h.handle(deliverCmd("1"), SELLER_ACTOR);

        h.handle(releaseCmd("1"), ACTOR);
        String out = h.handle(releaseYes("1"), ACTOR);

        assertThat(out).contains("平台费 2").contains("卖方实收 98");
        assertThat(out).doesNotContain("首单免平台费");
    }

    @Test
    @DisplayName("统计通道故障 → 按非首单收费（优惠不因异常被滥发），且不阻断放款主流程")
    void statsPortFailureFallsBackToNonFirstOrderFee() {
        com.tg.escrow.escrow.TraderStatsPort failing = new com.tg.escrow.escrow.TraderStatsPort() {
            @Override
            public com.tg.escrow.escrow.TraderStats of(long userId) {
                throw new IllegalStateException("统计通道故障（模拟）");
            }

            @Override
            public List<com.tg.escrow.escrow.TraderStats> allWithActivity() {
                return List.of();
            }
        };
        TradeCommandHandler h = handler(5, Duration.ZERO, clean(), AllowedCurrencies.all(),
                new UserIdMasker(TEST_SALT), com.tg.escrow.escrow.FeePolicy.of("0.02", "0", "0"), failing);
        placeOpenOrder(h);
        h.handle(lockCmd("1"), ACTOR);
        h.handle(deliverCmd("1"), SELLER_ACTOR);

        h.handle(releaseCmd("1"), ACTOR);
        String out = h.handle(releaseYes("1"), ACTOR);

        assertThat(out).as("统计故障不得阻断放款主流程").contains("已放款");
        assertThat(out).as("「无法判定」= 按非首单收费").contains("平台费 2");
        assertThat(out).as("不得凭空给出首单优惠").doesNotContain("首单免平台费");
    }

    @Test
    @DisplayName("/escrow my：只列自己参与的订单（含订单号）——买/卖两侧都可见，第三方看不到")
    void myListsOnlyOrdersIAmPartOf() {
        TradeCommandHandler h = handler(5, Duration.ZERO, clean());
        placeOpenOrder(h);   // 买方 = ACTOR(1001)、卖方 = SELLER(2002)

        String asBuyer = h.handle(new BotCommand("escrow", List.of("my")), ACTOR);
        String asSeller = h.handle(new BotCommand("escrow", List.of("my")), SELLER_ACTOR);
        String stranger = h.handle(new BotCommand("escrow", List.of("my")),
                new CommandActor(9999L, MemberRole.MEMBER));

        assertThat(asBuyer).as("买方能看到自己的单").contains("#1");
        assertThat(asSeller).as("卖方也能看到同一单").contains("#1");
        assertThat(stranger).as("第三方看不到别人的单").contains("没有").doesNotContain("#1");
    }

    @Test
    @DisplayName("handleReply：订单回执按角色附动作按钮；用法回执不挂按钮、仍引导表单")
    void handleReplyAttachesActionButtonsByRole() {
        TradeCommandHandler h = handler(5, Duration.ZERO, clean());
        placeOpenOrder(h);   // 买方 = ACTOR(1001)、卖方 = SELLER(2002)，状态 OPEN

        BotReply asBuyer = h.handleReply(new BotCommand("escrow", List.of("status", "1")), ACTOR, 100L);
        BotReply asSeller = h.handleReply(new BotCommand("escrow", List.of("status", "1")), SELLER_ACTOR, 100L);
        BotReply usage = h.handleReply(new BotCommand("escrow", List.of()), ACTOR, 100L);

        assertThat(asBuyer.buttons()).as("OPEN 时该由买方托管——按钮提示他这一步")
                .singleElement()
                .satisfies(b -> assertThat(b.callbackData()).isEqualTo("lk:1"));
        assertThat(asSeller.buttons()).as("卖方此刻没有可做的动作，不该给点不动的按钮").isEmpty();
        assertThat(usage.buttons()).isEmpty();
        assertThat(usage.offerWebApp()).as("用法回执仍引导去表单").isTrue();
    }

    @Test
    @DisplayName("create/confirm 的第二参是卖方 ID（不是订单号）——不得据此挂出别的订单的按钮")
    void createDoesNotTreatSellerIdAsOrderId() {
        TradeCommandHandler h = handler(5, Duration.ZERO, clean());
        placeOpenOrder(h);   // 订单 #1
        placeOpenOrder(h);   // 订单 #2（并发上限在装配里已放到 5）

        // 卖方 ID 写 2，与「订单 #2」的号撞上：若把第二参当成订单号，就会把 #2 的动作按钮
        // 挂到这条「创建预览」回执上——用户点下去操作的是**另一个单**。
        BotReply created = h.handleReply(new BotCommand("escrow",
                List.of("create", "2", "100", "USDT")), ACTOR, 100L);

        assertThat(created.buttons())
                .as("第二参是卖方 ID：不得当成订单号去挂按钮")
                .isEmpty();
    }

    @Test
    @DisplayName("confirm 成功 → 回执带指向「刚创建那一单」的托管按钮（订单号不是从参数里来的）")
    void confirmReplyCarriesLockButtonForJustCreatedOrder() {
        TradeCommandHandler h = handler(5, Duration.ZERO, clean());
        // confirm 的前提是同一请求先走过 create 预览（pending 登记 + 参数一致性校验）
        h.handle(createCmd("2002", "100", "USDT"), ACTOR);

        BotReply out = h.handleReply(new BotCommand("escrow",
                List.of("confirm", "2002", "100", "USDT")), ACTOR, 100L);

        assertThat(out.text()).as("前提：这一笔确实创建成功").contains("已创建订单");
        assertThat(out.buttons())
                .as("创建成功后该能一键托管，而不是让用户去翻订单号")
                .singleElement()
                .satisfies(b -> assertThat(b.callbackData()).isEqualTo("lk:1"));
    }

    @Test
    @DisplayName("confirm 未预览（无 pending）→ 不得挂按钮：没成功就没有可操作的单")
    void failedConfirmCarriesNoButtons() {
        TradeCommandHandler h = handler(5, Duration.ZERO, clean());
        placeOpenOrder(h);   // 已有一单在途（#1）——实现若"借最近一单充数"就会在这里露馅

        // 没走 create 预览就直接 confirm：命令层以 pending 校验拒绝它
        BotReply out = h.handleReply(new BotCommand("escrow",
                List.of("confirm", "2002", "100", "USDT")), ACTOR, 100L);

        assertThat(out.text()).as("前提：这一次确实没创建成功").doesNotContain("已创建订单");
        assertThat(out.buttons())
                .as("没成功就没有可操作的单——不能借已有订单 #1 的按钮充数")
                .isEmpty();
    }

    @Test
    @DisplayName("refund：任一方可发起协商退款 → 终态，且回执声明「链上未接入」")
    void refundAdvancesAndDeclaresChainGap() {
        TradeCommandHandler h = handler(5, Duration.ZERO, clean());
        placeOpenOrder(h);
        h.handle(lockCmd("1"), ACTOR);

        h.handle(refundCmd("1"), SELLER_ACTOR);
        String out = h.handle(refundYes("1"), SELLER_ACTOR);

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
        h.handle(releaseCmd("1"), ACTOR);
        h.handle(releaseYes("1"), ACTOR);

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
        h.handle(releaseYes("1"), ACTOR);

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
        h.handle(releaseCmd("1"), ACTOR);
        assertThat(h.handle(releaseYes("1"), ACTOR)).contains("放款给卖方");
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

    // ── 交易群（ET-05/06/39/40）：群内落单即绑定、终态静默、惰性归档 ─────────

    private static final long CHAT_ID = -1001234567890L;

    @Test
    @DisplayName("群内落单 → 绑定本群为交易群并置顶规则公告（ET-05/06）")
    void confirmInGroupBindsTradeGroupAndAnnounces() {
        Wiring w = wiring(5, Duration.ZERO, clean());
        w.handler().handle(createCmd("2002", "100", "USDT"), ACTOR);

        String out = w.handler().handle(confirmCmd("2002", "100", "USDT"), ACTOR, CHAT_ID);

        assertThat(out).as("回执要说清群绑定结果").contains("交易群").contains("已绑定本群");
        assertThat(w.groupPort().actions)
                .anyMatch(a -> a.startsWith("announce:" + CHAT_ID))
                .anyMatch(a -> a.startsWith("pin:" + CHAT_ID));
    }

    @Test
    @DisplayName("私聊落单 → 不带群提示（交易群可选，不噪声；引导走 /escrow guide）")
    void confirmInPrivateChatIsQuiet() {
        Wiring w = wiring(5, Duration.ZERO, clean());
        w.handler().handle(createCmd("2002", "100", "USDT"), ACTOR);

        String out = w.handler().handle(confirmCmd("2002", "100", "USDT"), ACTOR);

        assertThat(out).isEqualTo("已创建订单 #1");
    }

    @Test
    @DisplayName("订单放款（终态）→ 交易群进静默期并公告（ET-40）")
    void releaseSettlesTradeGroupIntoSilence() {
        Wiring w = wiring(5, Duration.ZERO, clean());
        w.handler().handle(createCmd("2002", "100", "USDT"), ACTOR);
        w.handler().handle(confirmCmd("2002", "100", "USDT"), ACTOR, CHAT_ID);
        w.handler().handle(lockCmd("1"), ACTOR, CHAT_ID);
        w.handler().handle(deliverCmd("1"), ACTOR, CHAT_ID);
        assertThat(w.groupStore().find(1L)).as("confirm 后群绑定应已建立（tradeId=1）").isPresent();

        w.handler().handle(releaseCmd("1"), ACTOR, CHAT_ID);
        String out = w.handler().handle(releaseYes("1"), ACTOR, CHAT_ID);

        assertThat(out).contains("已进入静默期");
        assertThat(w.groupPort().actions)
                .as("静默期公告必须发到绑定的群")
                .anyMatch(a -> a.startsWith("announce:" + CHAT_ID) && a.contains("静默期"));
    }

    @Test
    @DisplayName("静默期满后的群内交互 → 惰性归档：公告归档并退群（ET-39）")
    void reapAfterSilenceArchivesAndLeaves() {
        // 绑定与结算在 T0 推进；归档结算在 T0+7 天（静默期满）后的下一次交互
        StubTradeGroup.MapStore groupStore = StubTradeGroup.mapStore();
        StubTradeGroup.RecordingPort groupPort = StubTradeGroup.port();
        TradeCommandHandler atT0 = handlerWithGroups(5, Duration.ZERO, clean(), T0, groupStore, groupPort);
        atT0.handle(createCmd("2002", "100", "USDT"), ACTOR);
        atT0.handle(confirmCmd("2002", "100", "USDT"), ACTOR, CHAT_ID);
        atT0.handle(lockCmd("1"), ACTOR, CHAT_ID);
        atT0.handle(deliverCmd("1"), ACTOR, CHAT_ID);
        atT0.handle(releaseCmd("1"), ACTOR, CHAT_ID);
        atT0.handle(releaseYes("1"), ACTOR, CHAT_ID);

        TradeCommandHandler afterSilence =
                handlerWithGroups(5, Duration.ZERO, clean(), T0.plus(Duration.ofDays(7)), groupStore, groupPort);
        boolean reaped = afterSilence.reapIfDue(CHAT_ID);

        assertThat(reaped)
                .as("归档成立 = 调用方应跳过该消息处理；不再产出「发往已退出群」的回执文本")
                .isTrue();
        assertThat(groupPort.actions)
                .anyMatch(a -> a.startsWith("announce:" + CHAT_ID) && a.contains("归档"))
                .anyMatch(a -> a.equals("leave:" + CHAT_ID));
    }

    /** 指定时钟与群件的装配变体（惰性归档要跨静默期驱动时间）。 */
    private static TradeCommandHandler handlerWithGroups(int maxConcurrent, Duration cooldown,
                                                         TradeAdmissionContext ctx, Instant now,
                                                         StubTradeGroup.MapStore groupStore,
                                                         StubTradeGroup.RecordingPort groupPort) {
        return handlerWithLedgerAndGroups(maxConcurrent, cooldown, ctx, now, groupStore, groupPort,
                com.tg.escrow.escrow.FeePolicy.zero(), null);
    }

    /** 带费率与费用台账的装配变体（放款记账端到端用；走规范构造，偏好/存证腿显式置空）。 */
    private static TradeCommandHandler handlerWithLedgerAndGroups(
            int maxConcurrent, Duration cooldown, TradeAdmissionContext ctx, Instant now,
            StubTradeGroup.MapStore groupStore, StubTradeGroup.RecordingPort groupPort,
            com.tg.escrow.escrow.FeePolicy feePolicy,
            com.tg.escrow.escrow.FeeLedgerPort feeLedger) {
        TradeAdmissionGate gate = new TradeAdmissionGate(
                new TradeAdmissionPolicy(maxConcurrent, cooldown));
        TradeHistoryPort history = id -> new TradeAdmissionContext(
                id, ctx.activeTradeCount(), ctx.lastCompletedTradeAt(), ctx.hasUnresolvedDispute());
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        InMemoryStore store = new InMemoryStore();
        return new TradeCommandHandler(
                new EscrowTradeService(gate, history, store, AllowedCurrencies.all(), clock),
                new AmountTierPolicy(new BigDecimal("100"), new BigDecimal("1000")),
                new PendingTradeRegistry(PENDING_TTL, clock),
                store,
                inviteService(gate, history, store, clock),
                new InviteLink(BOT_USERNAME),
                new TradeReviewService(new InMemoryReviewStore(), clock),
                maintenanceService(clock),
                notifier(new RecordingNotifications()), StubTraderStats.EMPTY,
                new com.tg.escrow.escrow.TraderCreditService(),
                AllowedCurrencies.all(), new UserIdMasker(TEST_SALT),
                StubTradeGroup.service(groupStore, clock), groupPort, feePolicy,
                StubFraudLinkage.service(), StubDispute.service(clock),
                new MemoryPrefs(), null, feeLedger,
                new com.tg.escrow.escrow.ConfirmGate(Duration.ofMinutes(5), clock));
    }

    // ── ET-80 排名隐私：默认脱敏 + 用户主动公开 opt-in ──────────────────────────

    /** 测试用内存偏好（默认不公开），与 JPA 实现同一默认口径。 */
    private static final class MemoryPrefs implements com.tg.escrow.moderation.UserPreferencePort {
        private final java.util.Map<Long, RankVisibility> rows = new java.util.HashMap<>();

        @Override public String noticeModeOf(long userId) {
            return "all";
        }
        @Override public void setNoticeMode(long userId, String mode) {
        }
        @Override public RankVisibility rankVisibilityOf(long userId) {
            return rows.getOrDefault(userId, new RankVisibility(false, null));
        }
        @Override public void setRankPublic(long userId, boolean open, String name) {
            rows.put(userId, new RankVisibility(open, open ? name : null));
        }
    }

    private static com.tg.escrow.escrow.TraderStatsPort statsWithBuyer() {
        com.tg.escrow.escrow.TraderStats buyer = new com.tg.escrow.escrow.TraderStats(
                BUYER, 1, 0, 1, 0, 0, T0, T0, java.util.Map.of());
        return new com.tg.escrow.escrow.TraderStatsPort() {
            @Override public com.tg.escrow.escrow.TraderStats of(long userId) {
                return userId == BUYER ? buyer
                        : new com.tg.escrow.escrow.TraderStats(
                                userId, 0, 0, 0, 0, 0, T0, T0, java.util.Map.of());
            }
            @Override public java.util.List<com.tg.escrow.escrow.TraderStats> allWithActivity() {
                return java.util.List.of(buyer);
            }
        };
    }

    private TradeCommandHandler rankHandler(com.tg.escrow.moderation.UserPreferencePort prefs) {
        return handler(5, Duration.ZERO, clean(), AllowedCurrencies.all(),
                new UserIdMasker(TEST_SALT), com.tg.escrow.escrow.FeePolicy.zero(),
                statsWithBuyer(), prefs);
    }

    @Test
    @DisplayName("ET-80：未公开时榜单脱敏（显示 1***1，不出现用户名）")
    void rankIsMaskedByDefault() {
        MemoryPrefs prefs = new MemoryPrefs();
        TradeCommandHandler h = rankHandler(prefs);

        String out = h.handle(new BotCommand("escrow", List.of("rank")),
                new CommandActor(BUYER, MemberRole.MEMBER, "alpha_trader"));

        assertThat(out).contains("1***1").doesNotContain("alpha_trader");
    }

    @Test
    @DisplayName("ET-80：publish 后榜单显示用户名，不再脱敏")
    void publishRevealsUsernameOnRank() {
        MemoryPrefs prefs = new MemoryPrefs();
        TradeCommandHandler h = rankHandler(prefs);
        CommandActor actor = new CommandActor(BUYER, MemberRole.MEMBER, "alpha_trader");

        String receipt = h.handle(new BotCommand("escrow", List.of("publish")), actor);
        String rank = h.handle(new BotCommand("escrow", List.of("rank")), actor);

        assertThat(receipt).contains("@alpha_trader");
        assertThat(rank).contains("@alpha_trader").doesNotContain("1***1");
    }

    @Test
    @DisplayName("ET-80：无 @username 时 publish 被拒（先去设置，不落成无效开关）")
    void publishWithoutUsernameIsRejected() {
        MemoryPrefs prefs = new MemoryPrefs();
        TradeCommandHandler h = rankHandler(prefs);

        String receipt = h.handle(new BotCommand("escrow", List.of("publish")),
                new CommandActor(BUYER, MemberRole.MEMBER));

        assertThat(receipt).contains("没有设置 Telegram 用户名");
        // 关键：被拒后榜单仍脱敏——开关没有被错误置为公开。
        String rank = h.handle(new BotCommand("escrow", List.of("rank")),
                new CommandActor(BUYER, MemberRole.MEMBER, "alpha_trader"));
        assertThat(rank).contains("1***1").doesNotContain("alpha_trader");
    }

    @Test
    @DisplayName("ET-80：publish 再 unpublish，榜单恢复脱敏")
    void unpublishRestoresMasking() {
        MemoryPrefs prefs = new MemoryPrefs();
        TradeCommandHandler h = rankHandler(prefs);
        CommandActor actor = new CommandActor(BUYER, MemberRole.MEMBER, "alpha_trader");

        h.handle(new BotCommand("escrow", List.of("publish")), actor);
        String off = h.handle(new BotCommand("escrow", List.of("unpublish")), actor);
        String rank = h.handle(new BotCommand("escrow", List.of("rank")), actor);

        assertThat(off).contains("隐藏");
        assertThat(rank).contains("1***1").doesNotContain("alpha_trader");
    }

    // ---- S5 链上存证腿（ET-43）：空实现误报「已上链」的缺陷修复 + 真腿语义 ----

    private static String validChainAddr(byte fill) {
        byte[] hash = new byte[32];
        java.util.Arrays.fill(hash, fill);
        return org.ton.ton4j.address.Address
                .of(org.ton.ton4j.address.Address.BOUNCEABLE_TAG, 0, hash)
                .toString(true, true, true);
    }

    private static EscrowOrderLookupPort orderLookup(String chainAddressOrNull) {
        EscrowOrder order = new EscrowOrder(BUYER, SELLER, new BigDecimal("1"), "TON", T0);
        order.assignId(1L);
        if (chainAddressOrNull != null) {
            order.attachChainContractAddress(chainAddressOrNull, T0);
        }
        return new EscrowOrderLookupPort() {
            @Override
            public Optional<EscrowOrder> byId(long id) {
                return Optional.of(order);
            }

            @Override
            public List<EscrowOrder> recentFor(long userId, int limit) {
                return List.of();
            }
        };
    }

    /** 真 ChainEvidenceSink + 替身写链接缝（记录投递目标）。 */
    private static ChainEvidenceSink chainSink(String chainAddressOrNull, List<String> targets) {
        ChainGateway gateway = ChainGateway.of(
                        new ChainSettings(validChainAddr((byte) 0x77), true),
                        (master, owner) -> validChainAddr((byte) 0x77))
                .withUpgradeWriters((contract, body, value) -> targets.add(contract),
                        contract -> new UpgradeStatus(BigInteger.ZERO, 0L));
        return new ChainEvidenceSink(orderLookup(chainAddressOrNull), gateway, TEST_SALT);
    }

    private String submitEvidence(TradeCommandHandler h) {
        h.handle(lockCmd("1"), ACTOR);
        h.handle(disputeCmd("1"), SELLER_ACTOR);
        return h.handle(new BotCommand("escrow", List.of("evidence", "1", "物流单号 123")),
                SELLER_ACTOR);
    }

    @Test
    @DisplayName("证据（S5）：链上腿未接线 → 回执如实报「未成功」，不误报「已上链」")
    void evidenceWithoutChainSinkReportsNotSuccessful() {
        // 19 参兼容构造器路径：sink=null——腿必须抛（而不是"不抛的空实现"），
        // 否则 trySave 会把它当作成功、回执显示「已上链」（本用例是该缺陷的回归钉）
        TradeCommandHandler h = handler(5, Duration.ZERO, clean());
        placeOpenOrder(h);

        String out = submitEvidence(h);

        assertThat(out).contains("链上存证：未成功").doesNotContain("已上链");
        assertThat(out).contains("⚠️");
    }

    @Test
    @DisplayName("证据（S5）：链上腿成功 → 「已上链」；投递目标=订单链上合约地址")
    void evidenceWithWiredSinkReportsOnChain() {
        String address = validChainAddr((byte) 0x99);
        List<String> targets = new ArrayList<>();
        TradeCommandHandler h = handler(5, Duration.ZERO, clean(), AllowedCurrencies.all(),
                new UserIdMasker(TEST_SALT), com.tg.escrow.escrow.FeePolicy.zero(),
                StubTraderStats.EMPTY, null, chainSink(address, targets));
        placeOpenOrder(h);

        String out = submitEvidence(h);

        assertThat(out).contains("链上存证：已上链");
        assertThat(targets).as("RecordEvidence 投往订单的合约地址").containsExactly(address);
    }

    @Test
    @DisplayName("证据（S5）：订单未部署链上合约 → 「未成功」+ ⚠️（sink 如实抛，回执不美化）")
    void evidenceWithoutDeployedContractReportsNotSuccessful() {
        List<String> targets = new ArrayList<>();
        TradeCommandHandler h = handler(5, Duration.ZERO, clean(), AllowedCurrencies.all(),
                new UserIdMasker(TEST_SALT), com.tg.escrow.escrow.FeePolicy.zero(),
                StubTraderStats.EMPTY, null, chainSink(null, targets));
        placeOpenOrder(h);

        String out = submitEvidence(h);

        assertThat(out).contains("链上存证：未成功").doesNotContain("已上链");
        assertThat(targets).as("未部署时不得发生任何链上发送").isEmpty();
    }

    @Test
    @DisplayName("缺参回聚焦提示：单条命令用法 + 全量入口——不再甩 19 条全量 USAGE")
    void missingArgsGiveFocusedUsage() {
        TradeCommandHandler h = handler(5, Duration.ZERO, clean());

        String lockMissing = h.handle(new BotCommand("escrow", List.of("lock")), ACTOR);
        assertThat(lockMissing).contains("用法：/escrow lock <订单号>").contains("查看全部命令");
        assertThat(lockMissing).as("不再回全量长文（USAGE 首条是 invite）")
                .doesNotContain("/escrow invite");

        assertThat(h.handle(new BotCommand("escrow", List.of("status")), ACTOR))
                .contains("用法：/escrow status <订单号>");
        assertThat(h.handle(new BotCommand("escrow", List.of("deliver")), ACTOR))
                .contains("用法：/escrow deliver <订单号>");
        assertThat(h.handle(new BotCommand("escrow", List.of("create", "2002")), ACTOR))
                .contains("用法：/escrow create <卖方ID> <金额> <币种>");
    }

    @Test
    @DisplayName("回复式创建：/escrow create <金额> <币种> + 回复对方 → 卖方=被回复者；两步流走完")
    void replyFormCreate() {
        TradeCommandHandler h = handler(5, Duration.ZERO, clean());

        BotReply preview = h.handleReply(
                new BotCommand("escrow", List.of("create", "100", "USDT")), ACTOR, 100L, SELLER);
        assertThat(preview.text())
                .as("回复形态的预览应教用户『回复对方的同一条消息发 confirm』，而不是给他看不懂的数字 ID")
                .contains("风险提示")
                .contains("回复")
                .doesNotContain(String.valueOf(SELLER));

        BotReply confirmed = h.handleReply(
                new BotCommand("escrow", List.of("confirm", "100", "USDT")), ACTOR, 100L, SELLER);
        assertThat(confirmed.text())
                .as("回复形态的 confirm 走通两步流（与预览的 request 完全一致）")
                .contains("已创建订单")
                .doesNotContain("请先预览");
    }

    @Test
    @DisplayName("显式参数优先：3 参形态同时回复了某人 → 用显式卖方 ID（回复只是缺省时的便利）")
    void explicitSellerIdWinsOverReply() {
        TradeCommandHandler h = handler(5, Duration.ZERO, clean());

        BotReply preview = h.handleReply(
                new BotCommand("escrow", List.of("create", "2002", "100", "USDT")), ACTOR, 100L, 3003L);

        assertThat(preview.text())
                .as("显式形态的预览仍给出带 ID 的 confirm 指令")
                .contains("confirm 2002 100 USDT");
    }

    /** 记录型台账（放款记账端到端断言用）。 */
    private static final class RecordingFeeLedger
            implements com.tg.escrow.escrow.FeeLedgerPort {
        final List<Entry> entries = new java.util.ArrayList<>();

        @Override
        public void record(Entry entry) {
            entries.add(entry);
        }
    }

    @Test
    @DisplayName("放款记账端到端（ET-29/30）：release 走完 → 台账收到该订单账目（费率 2%、首单豁免标记）")
    void releaseRecordsFeeLedger() {
        RecordingFeeLedger ledger = new RecordingFeeLedger();
        StubTradeGroup.MapStore groupStore = StubTradeGroup.mapStore();
        TradeCommandHandler h = handlerWithLedgerAndGroups(5, Duration.ZERO, clean(),
                Instant.parse("2026-09-25T12:00:00Z"), groupStore, StubTradeGroup.port(),
                com.tg.escrow.escrow.FeePolicy.of("0.02", "0", "0"), ledger);
        placeOpenOrder(h);
        h.handle(lockCmd("1"), ACTOR);
        h.handle(deliverCmd("1"), SELLER_ACTOR);

        h.handle(releaseCmd("1"), ACTOR);
        h.handle(releaseYes("1"), ACTOR);

        assertThat(ledger.entries)
                .as("放款成功即落账（接线点存在性被钉住——防「记账调用被遗忘」）")
                .singleElement().satisfies(e -> {
                    assertThat(e.orderId()).isEqualTo(1L);
                    assertThat(e.currency()).isEqualTo("USDT");
                    assertThat(e.grossAmount()).isEqualByComparingTo("100");
                    assertThat(e.platformFee()).as("卖方无成交史 → 首单豁免，实收 0")
                            .isEqualByComparingTo("0");
                    assertThat(e.sellerNet()).isEqualByComparingTo("100");
                    assertThat(e.firstOrderWaived()).isTrue();
                });
    }
}
