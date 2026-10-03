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

import com.tg.escrow.core.CommandActor;
import com.tg.escrow.core.IncomingMessage;
import com.tg.escrow.core.MemberRole;
import com.tg.escrow.escrow.AmountTierPolicy;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
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
 * 不命中不响应；escrow 命令 → 交易处理器（两步流：create 预览 → confirm 落单）；其它命令 → 帮助。
 */
class BotDispatcherTest {

    private static final Instant T0 = Instant.parse("2026-09-23T00:00:00Z");
    private static final CommandActor ACTOR = new CommandActor(1001L, MemberRole.MEMBER);

    private static final class InMemoryStore implements EscrowOrderStore, EscrowOrderLookupPort {
        private final java.util.Map<Long, EscrowOrder> byId = new java.util.HashMap<>();
        private long seq;

        @Override
        public EscrowOrder save(EscrowOrder order) {
            // 与生产同语义：只对新建订单回填 ID（无条件重赋会让同一订单在多命令间换号）
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

    /** 本类不测邀请路径——给个空实现让装配成形即可。 */
    private static final class NoopInviteStore implements TradeInviteStore {
        @Override
        public TradeInvite save(TradeInvite invite) {
            return invite;
        }

        @Override
        public java.util.Optional<TradeInvite> byToken(String token) {
            return java.util.Optional.empty();
        }
    }

    /** 真实 service + 真实 registry 的交易处理器（不 mock 中间层）。 */
    private static TradeCommandHandler tradeHandler() {
        return tradeHandler(StubTraderStats.EMPTY);
    }

    /** 可指定统计来源的同款装配——榜单验收需要真实数据，其余用例沿用空统计。 */
    private static TradeCommandHandler tradeHandler(com.tg.escrow.escrow.TraderStatsPort stats) {
        Clock clock = Clock.fixed(T0, ZoneOffset.UTC);
        return tradeHandler(stats, StubTradeGroup.service(clock), StubTradeGroup.port(),
                com.tg.escrow.escrow.FeePolicy.zero(), StubFraudLinkage.service());
    }

    /** 可指定统计来源与交易群件的同款装配（交易群验收要可断言的群件）。 */
    private static TradeCommandHandler tradeHandler(com.tg.escrow.escrow.TraderStatsPort stats,
                                                    com.tg.escrow.escrow.TradeGroupService groupService,
                                                    com.tg.escrow.core.TradeGroupPort groupPort,
                                                    com.tg.escrow.escrow.FeePolicy feePolicy,
                                                    FraudLinkageService fraudLinkage) {
        TradeAdmissionGate gate = new TradeAdmissionGate(new TradeAdmissionPolicy(5, Duration.ZERO));
        TradeHistoryPort history = id -> new TradeAdmissionContext(id, 0, null, false);
        Clock clock = Clock.fixed(T0, ZoneOffset.UTC);
        InMemoryStore store = new InMemoryStore();
        return new TradeCommandHandler(
                new EscrowTradeService(gate, history, store, AllowedCurrencies.all(), clock),
                new AmountTierPolicy(new BigDecimal("100"), new BigDecimal("1000")),
                new PendingTradeRegistry(Duration.ofMinutes(10), clock),
                store,   // 交易群验收要走 T2 查询（lock/release 按单号找单）
                new TradeInviteService(gate, history, new NoopInviteStore(), new InMemoryStore(),
                        Duration.ofHours(24), () -> "tok0", AllowedCurrencies.all(), clock),
                new InviteLink("mybot"),
                new TradeReviewService(new TradeReviewStore() {
                    @Override
                    public void record(long orderId, long reviewerId, int score, java.time.Instant at) {
                    }

                    @Override
                    public java.util.Set<Long> reviewersOf(long orderId) {
                        return java.util.Set.of();
                    }
                }, clock),
                maintenanceService(clock),
                new TradeNotifier(new BotReplyPort() {
                    @Override
                    public void sendText(long chatId, String text) {
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
                        // 同上：本替身不区分通知策略（策略语义由 TradeNotifierTest 覆盖）
                        sendText(chatId, text);
                    }

                    @Override
                    public void sendText(long chatId, String text,
                                         com.tg.escrow.core.NoticePolicy policy) {
                    }

                    @Override
                    public void sendTextWithWebApp(long chatId, String text, String buttonText,
                                                   String url) {
                    }

                    @Override
                    public void ackCallback(String callbackQueryId) {
                    }
                 }, clock, null, new com.tg.escrow.core.UserIdMasker("test-salt")),
                stats, new com.tg.escrow.escrow.TraderCreditService(),
                AllowedCurrencies.all(), new com.tg.escrow.core.UserIdMasker("test-salt"),
                groupService, groupPort, feePolicy, fraudLinkage, StubDispute.service(clock),
                new com.tg.escrow.escrow.ConfirmGate(Duration.ofMinutes(5), clock));
    }

    /** 本类不测维护期——装配成形即可（5 选项 + 默认第 3 项）。 */
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
    private static BotDispatcher dispatcher() {
        return new BotDispatcher(tradeHandler(), "mybot");
    }

    /** 带指定榜单数据的同款装配（Wave 2 验收：{@code /escrow rank} 的渲染与脱敏）。 */
    private static BotDispatcher dispatcherWithStats(com.tg.escrow.escrow.TraderStatsPort stats) {
        return new BotDispatcher(tradeHandler(stats), "mybot");
    }

    /** 交易群验收装配：可断言的群件 + 可指定时刻的群时钟（惰性归档要跨静默期驱动时间）。 */
    private static BotDispatcher dispatcherWithGroups(StubTradeGroup.MapStore groupStore,
                                                      StubTradeGroup.RecordingPort groupPort,
                                                      Instant groupClockNow) {
        com.tg.escrow.escrow.TradeGroupService groupService = StubTradeGroup.service(groupStore,
                Clock.fixed(groupClockNow, ZoneOffset.UTC));
        return new BotDispatcher(tradeHandler(StubTraderStats.EMPTY, groupService, groupPort,
                com.tg.escrow.escrow.FeePolicy.zero(), StubFraudLinkage.service()),
                "mybot");
    }

    @Test
    @DisplayName("榜单：/escrow rank 回执含榜单头与等级徽标，且**不含任何明文用户 ID**（v1 强制匿名）")
    void rankCommandRendersAnonymizedLeaderboard() {
        long highId = 9100001L;
        long lowId = 9100002L;
        java.util.Map<Long, Integer> spread = java.util.Map.of(1L, 1, 2L, 1, 3L, 1, 4L, 1, 5L, 1);
        java.time.Instant t0 = java.time.Instant.parse("2026-03-01T00:00:00Z");
        var high = new com.tg.escrow.escrow.TraderStats(
                highId, 35, 0, 35, 10, 10, t0, t0.plusSeconds(364L * 86400), spread);
        var low = new com.tg.escrow.escrow.TraderStats(
                lowId, 5, 0, 5, 0, 0, t0, t0.plusSeconds(364L * 86400), spread);

        BotReply reply = dispatcherWithStats(new StubTraderStats(java.util.List.of(low, high))).handle(
                IncomingMessage.text(100L, ACTOR.userId(), 1L, "/escrow rank 5"), ACTOR);

        assertThat(reply).as("榜单回执必须存在").isNotNull();
        String text = reply.text();
        assertThat(text).contains("交易排行").contains("评分 88").contains("评分 50");
        assertThat(text).as("等级徽标随条目出现").contains("🥇");
        assertThat(text)
                .as("明文 ID（9100001/9100002）绝不该出现在公开榜单里——脱敏后形如 9***1")
                .doesNotContain(String.valueOf(highId))
                .doesNotContain(String.valueOf(lowId));
    }

    @Test
    @DisplayName("榜单：条数非法 → 给可读提示；空榜 → 明示「暂无数据」而不是空白回执")
    void rankCommandRejectsBadSizeAndHandlesEmpty() {
        BotDispatcher dispatcher = dispatcher();

        assertThat(dispatcher.handle(
                IncomingMessage.text(100L, ACTOR.userId(), 1L, "/escrow rank abc"), ACTOR).text())
                .contains("必须是数字");
        assertThat(dispatcher.handle(
                IncomingMessage.text(100L, ACTOR.userId(), 1L, "/escrow rank 99"), ACTOR).text())
                .contains("1–20");
        assertThat(dispatcher.handle(
                IncomingMessage.text(100L, ACTOR.userId(), 1L, "/escrow rank"), ACTOR).text())
                .contains("暂无数据");
    }

    @Test
    @DisplayName("信用摘要：/escrow credit → 回执含自己的评分、等级与五维拆解（Wave 3 的触达口）")
    void creditCommandRendersOwnSummary() {
        long me = ACTOR.userId();
        java.time.Instant t0 = java.time.Instant.parse("2026-03-01T00:00:00Z");
        java.util.Map<Long, Integer> spread = java.util.Map.of(1L, 1, 2L, 1, 3L, 1, 4L, 1, 5L, 1);
        var mine = new com.tg.escrow.escrow.TraderStats(
                me, 35, 0, 35, 10, 10, t0, t0.plusSeconds(364L * 86400), spread);

        BotReply reply = dispatcherWithStats(new StubTraderStats(java.util.List.of(mine))).handle(
                IncomingMessage.text(100L, me, 1L, "/escrow credit"), ACTOR);

        assertThat(reply).as("信用摘要必须有回执").isNotNull();
        assertThat(reply.text())
                .contains("信用摘要").contains("88/100").contains("🥇").contains("金牌")
                .contains("完成交易 35 笔").contains("好评率：100%");
    }

    @Test
    @DisplayName("信用摘要：带他人 ID → 明确拒绝，回执不含那人任何信息（榜单匿名不能从这条路绕开）")
    void creditCommandRefusesOtherUsers() {
        long other = 9100001L;
        java.time.Instant t0 = java.time.Instant.parse("2026-03-01T00:00:00Z");
        java.util.Map<Long, Integer> spread = java.util.Map.of(1L, 1, 2L, 1, 3L, 1, 4L, 1, 5L, 1);
        var otherStats = new com.tg.escrow.escrow.TraderStats(
                other, 35, 0, 35, 10, 10, t0, t0.plusSeconds(364L * 86400), spread);

        BotReply reply = dispatcherWithStats(new StubTraderStats(java.util.List.of(otherStats)))
                .handle(IncomingMessage.text(100L, ACTOR.userId(), 1L, "/escrow credit " + other),
                        ACTOR);

        assertThat(reply.text()).contains("只能查自己");
        assertThat(reply.text())
                .as("拒绝就彻底拒绝：分数、徽标、ID 一个都不该泄漏")
                .doesNotContain("88").doesNotContain("🥇").doesNotContain(String.valueOf(other));
    }

    @Test
    @DisplayName("信用摘要：无成交史 → 如实标注「尚无成交记录」，不假装有信用")
    void creditCommandForNewUserIsHonest() {
        BotReply reply = dispatcher().handle(
                IncomingMessage.text(100L, ACTOR.userId(), 1L, "/escrow credit"), ACTOR);

        assertThat(reply.text()).contains("尚无成交记录").contains("40/100").contains("新手");
    }

    @Test
    @DisplayName("非命令消息（无违禁词）→ 不响应（返回 null）")
    void plainMessageGetsNoReply() {
        assertThat(dispatcher().handle(
                IncomingMessage.text(100L, ACTOR.userId(), 1L, "大家好，这单怎么走"), ACTOR)).isNull();
    }

    @Test
    @DisplayName("escrow 命令 → 路由到交易处理器：先 create 预览、再 confirm，回执含订单号")
    void escrowCommandRouted() {
        BotDispatcher d = dispatcher();

        d.handle(IncomingMessage.text(100L, ACTOR.userId(), 1L, "/escrow create 2002 100 USDT"), ACTOR);
        BotReply reply = d.handle(
                IncomingMessage.text(100L, ACTOR.userId(), 2L, "/escrow confirm 2002 100 USDT"), ACTOR);

        assertThat(reply.text()).contains("已创建订单");
        assertThat(reply.offerWebApp()).isFalse();   // 正常落单回执不带表单按钮
    }

    @Test
    @DisplayName("裸 /escrow（无子命令）→ 用法回执且引导表单（Wave 3：用户不必记命令语法）")
    void bareEscrowOffersWebAppForm() {
        BotReply reply = dispatcher().handle(
                IncomingMessage.text(100L, ACTOR.userId(), 1L, "/escrow"), ACTOR);

        assertThat(reply.text()).isEqualTo(TradeCommandHandler.USAGE);
        assertThat(reply.offerWebApp()).isTrue();
    }

    @Test
    @DisplayName("其它命令 → 帮助回执（含用法）")
    void unknownCommandGetsHelp() {
        BotReply reply = dispatcher().handle(
                IncomingMessage.text(100L, ACTOR.userId(), 1L, "/whatever"), ACTOR);

        assertThat(reply.text()).contains("escrow").contains("用法");
        assertThat(reply.offerWebApp()).isTrue();   // 帮助场景引导去表单
    }

    @Test
    @DisplayName("@botname 后缀：发给别的 bot 的命令不响应")
    void commandForOtherBotIgnored() {
        assertThat(dispatcher().handle(
                IncomingMessage.text(100L, ACTOR.userId(), 1L, "/escrow@otherbot confirm 2002 100 USDT"),
                ACTOR)).isNull();
    }

    @Test
    @DisplayName("频道帖里的命令不执行业务动作（没有个人主体：不该凭空造出 0 号用户）")
    void channelPostDoesNotExecuteBusinessCommands() {
        BotReply reply = dispatcher().handle(IncomingMessage.channelPost(100L, 1L,
                        "/escrow create 2002 100 USDT", IncomingMessage.MediaKind.NONE, null, null,
                        com.tg.escrow.core.ChatKind.SUPERGROUP, "RogueChannel"),
                new CommandActor(0L, MemberRole.MEMBER));

        assertThat(reply)
                .as("命令不被路由 → 既无风险提示也无落单回执（若被路由，create 会回风险提示）")
                .isNull();
    }

    @Test
    @DisplayName("null 文本 / null actor → 不响应（不抛，群里什么都有）")
    void nullsGetNoReply() {
        BotDispatcher d = dispatcher();

        assertThat(d.handle(IncomingMessage.text(100L, ACTOR.userId(), 1L, null), ACTOR)).isNull();
        assertThat(d.handle(
                IncomingMessage.text(100L, ACTOR.userId(), 1L, "/escrow confirm 2002 100 USDT"), null))
                .isNull();
    }

    // ── 交易群用户级验收（ET-05/06/39/40）：群会话全链（IncomingMessage → 回执）────

    private static final long GROUP_CHAT = -1001234567890L;

    @Test
    @DisplayName("验收①：群会话落单 → 回执含「交易群：已绑定本群，规则公告已置顶」+ 公告与置顶发生")
    void groupConfirmBindsTradeGroup() {
        StubTradeGroup.MapStore groupStore = StubTradeGroup.mapStore();
        StubTradeGroup.RecordingPort groupPort = StubTradeGroup.port();
        BotDispatcher d = dispatcherWithGroups(groupStore, groupPort, T0);

        d.handle(IncomingMessage.text(GROUP_CHAT, ACTOR.userId(), 1L,
                "/escrow create 2002 100 USDT"), ACTOR);
        String out = d.handle(IncomingMessage.text(GROUP_CHAT, ACTOR.userId(), 2L,
                "/escrow confirm 2002 100 USDT"), ACTOR).text();

        assertThat(out).contains("已创建订单 #1").contains("交易群：已绑定本群，规则公告已置顶");
        assertThat(groupPort.actions)
                .anyMatch(a -> a.startsWith("announce:" + GROUP_CHAT))
                .anyMatch(a -> a.startsWith("pin:" + GROUP_CHAT));
    }

    @Test
    @DisplayName("验收②：群会话走完 lock→deliver→release → 放款回执含「已进入静默期」+ 公告发到群")
    void groupReleaseEntersSilence() {
        StubTradeGroup.MapStore groupStore = StubTradeGroup.mapStore();
        StubTradeGroup.RecordingPort groupPort = StubTradeGroup.port();
        BotDispatcher d = dispatcherWithGroups(groupStore, groupPort, T0);
        d.handle(IncomingMessage.text(GROUP_CHAT, ACTOR.userId(), 1L,
                "/escrow create 2002 100 USDT"), ACTOR);
        d.handle(IncomingMessage.text(GROUP_CHAT, ACTOR.userId(), 2L,
                "/escrow confirm 2002 100 USDT"), ACTOR);
        d.handle(IncomingMessage.text(GROUP_CHAT, ACTOR.userId(), 3L, "/escrow lock 1"), ACTOR);
        d.handle(IncomingMessage.text(GROUP_CHAT, ACTOR.userId(), 4L, "/escrow deliver 1"), ACTOR);

        d.handle(IncomingMessage.text(GROUP_CHAT, ACTOR.userId(), 5L,
                "/escrow release 1"), ACTOR);
        String out = d.handle(IncomingMessage.text(GROUP_CHAT, ACTOR.userId(), 6L,
                "/escrow release 1 --yes"), ACTOR).text();

        assertThat(out).contains("已放款").contains("已进入静默期（期满自动归档）");
        assertThat(groupPort.actions)
                .anyMatch(a -> a.startsWith("announce:" + GROUP_CHAT) && a.contains("静默期"));
    }

    @Test
    @DisplayName("验收③：静默期满后群内任意消息 → 归档公告 + 退群（不产生群内回执——bot 已退，发不出）")
    void groupMessageAfterSilenceArchives() {
        StubTradeGroup.MapStore groupStore = StubTradeGroup.mapStore();
        StubTradeGroup.RecordingPort groupPort = StubTradeGroup.port();
        BotDispatcher d0 = dispatcherWithGroups(groupStore, groupPort, T0);
        d0.handle(IncomingMessage.text(GROUP_CHAT, ACTOR.userId(), 1L,
                "/escrow create 2002 100 USDT"), ACTOR);
        d0.handle(IncomingMessage.text(GROUP_CHAT, ACTOR.userId(), 2L,
                "/escrow confirm 2002 100 USDT"), ACTOR);
        d0.handle(IncomingMessage.text(GROUP_CHAT, ACTOR.userId(), 3L, "/escrow lock 1"), ACTOR);
        d0.handle(IncomingMessage.text(GROUP_CHAT, ACTOR.userId(), 4L, "/escrow deliver 1"), ACTOR);
        d0.handle(IncomingMessage.text(GROUP_CHAT, ACTOR.userId(), 5L, "/escrow release 1"), ACTOR);
        d0.handle(IncomingMessage.text(GROUP_CHAT, ACTOR.userId(), 6L, "/escrow release 1 --yes"), ACTOR);

        // 静默期（7 天）满后的下一次群内交互（普通消息即可）触发惰性归档
        BotDispatcher d7 = dispatcherWithGroups(groupStore, groupPort, T0.plus(Duration.ofDays(7)));
        var out = d7.handle(
                IncomingMessage.text(GROUP_CHAT, ACTOR.userId(), 6L, "大家好"), ACTOR);

        assertThat(out)
                .as("归档后 bot 已退群——不得再返回要发往该群的回执（回执照发会 403 bot is not a member）")
                .isNull();
        assertThat(groupPort.actions)
                .anyMatch(a -> a.startsWith("announce:" + GROUP_CHAT) && a.contains("归档"))
                .anyMatch(a -> a.equals("leave:" + GROUP_CHAT));
    }

    // ── ET-29 用户级验收：费率配置后放款回执披露平台费（费率 0 时静默）────────

    @Test
    @DisplayName("验收：配置平台费率后 /escrow release 回执含「平台费 2 …卖方实收 98」且标注平台费≠仲裁费")
    void releaseReceiptDisclosesPlatformFee() {
        StubTradeGroup.MapStore groupStore = StubTradeGroup.mapStore();
        StubTradeGroup.RecordingPort groupPort = StubTradeGroup.port();
        com.tg.escrow.escrow.TradeGroupService groupService =
                StubTradeGroup.service(groupStore, Clock.fixed(T0, ZoneOffset.UTC));
        com.tg.escrow.escrow.FeePolicy fee = com.tg.escrow.escrow.FeePolicy.of("0.02", "0", "0");
        // 卖方 2002 已有成交史 → 非首单，走基准平台费（首单豁免的披露另有专测，ET-35 · B2）
        var stats = new StubTraderStats(java.util.List.of(new com.tg.escrow.escrow.TraderStats(
                2002L, 1, 0, 1, 0, 0, null, null, java.util.Map.of())));
        BotDispatcher d = new BotDispatcher(
                tradeHandler(stats, groupService, groupPort, fee, StubFraudLinkage.service()),
                "mybot");
        d.handle(IncomingMessage.text(GROUP_CHAT, ACTOR.userId(), 1L,
                "/escrow create 2002 100 USDT"), ACTOR);
        d.handle(IncomingMessage.text(GROUP_CHAT, ACTOR.userId(), 2L,
                "/escrow confirm 2002 100 USDT"), ACTOR);
        d.handle(IncomingMessage.text(GROUP_CHAT, ACTOR.userId(), 3L, "/escrow lock 1"), ACTOR);
        d.handle(IncomingMessage.text(GROUP_CHAT, ACTOR.userId(), 4L, "/escrow deliver 1"), ACTOR);

        d.handle(IncomingMessage.text(GROUP_CHAT, ACTOR.userId(), 5L,
                "/escrow release 1"), ACTOR);
        String out = d.handle(IncomingMessage.text(GROUP_CHAT, ACTOR.userId(), 6L,
                "/escrow release 1 --yes"), ACTOR).text();

        assertThat(out).contains("平台费 2 USDT").contains("卖方实收 98 USDT")
                .contains("平台费 ≠ 仲裁费");
    }

    @Test
    @DisplayName("验收：费率 0（默认）→ 放款回执静默不提费用（不收费不该占回执）")
    void zeroFeeRateStaysSilent() {
        StubTradeGroup.MapStore groupStore = StubTradeGroup.mapStore();
        StubTradeGroup.RecordingPort groupPort = StubTradeGroup.port();
        BotDispatcher d = dispatcherWithGroups(groupStore, groupPort, T0);
        d.handle(IncomingMessage.text(GROUP_CHAT, ACTOR.userId(), 1L,
                "/escrow create 2002 100 USDT"), ACTOR);
        d.handle(IncomingMessage.text(GROUP_CHAT, ACTOR.userId(), 2L,
                "/escrow confirm 2002 100 USDT"), ACTOR);
        d.handle(IncomingMessage.text(GROUP_CHAT, ACTOR.userId(), 3L, "/escrow lock 1"), ACTOR);
        d.handle(IncomingMessage.text(GROUP_CHAT, ACTOR.userId(), 4L, "/escrow deliver 1"), ACTOR);

        d.handle(IncomingMessage.text(GROUP_CHAT, ACTOR.userId(), 5L,
                "/escrow release 1"), ACTOR);
        String out = d.handle(IncomingMessage.text(GROUP_CHAT, ACTOR.userId(), 6L,
                "/escrow release 1 --yes"), ACTOR).text();

        assertThat(out).contains("已放款").doesNotContain("平台费");
    }
}
