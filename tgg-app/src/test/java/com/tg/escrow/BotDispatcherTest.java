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
import com.tg.escrow.core.CommandActor;
import com.tg.escrow.core.GroupAdminPort;
import com.tg.escrow.core.KeywordAutoReply;
import com.tg.escrow.core.MemberRole;
import com.tg.escrow.core.MemberRolePort;
import com.tg.escrow.core.ModerationOrchestrator;
import com.tg.escrow.escrow.AmountTierPolicy;
import com.tg.escrow.escrow.EscrowOrder;
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

/**
 * Bot 命令分发器（S1 命令路由 + GM-06 违禁词接线）的行为固定测试。
 *
 * <p>路由：非命令 → 违禁词检查（<b>命中给出处置回执</b>——热更新词库的真实生产消费者）→
 * 不命中不响应；escrow 命令 → 交易处理器（两步流：create 预览 → confirm 落单）；其它命令 → 帮助。
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
        TradeAdmissionGate gate = new TradeAdmissionGate(new TradeAdmissionPolicy(5, Duration.ZERO));
        TradeHistoryPort history = id -> new TradeAdmissionContext(id, 0, null, false);
        Clock clock = Clock.fixed(T0, ZoneOffset.UTC);
        return new TradeCommandHandler(
                new EscrowTradeService(gate, history, new InMemoryStore(), clock),
                new AmountTierPolicy(new BigDecimal("100"), new BigDecimal("1000")),
                new PendingTradeRegistry(Duration.ofMinutes(10), clock),
                orderId -> java.util.Optional.empty(),   // 本类不测 T2 查询，stub 即可
                new TradeInviteService(gate, history, new NoopInviteStore(), new InMemoryStore(),
                        Duration.ofHours(24), () -> "tok0", clock),
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
                maintenanceService(clock));
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

    /** 本类不测群管理路径——给个空实现让装配成形（命令由 ModerationCommandHandlerTest 覆盖）。 */
    private static ModerationCommandHandler noopModeration() {
        ModerationOrchestrator orch = new ModerationOrchestrator(new GroupAdminPort() {
            @Override
            public void kick(long guildId, long userId) {
            }

            @Override
            public void ban(long guildId, long userId) {
            }

            @Override
            public void mute(long guildId, long userId, java.time.Duration duration) {
            }

            @Override
            public void deleteMessage(long guildId, long messageId) {
            }
        });
        com.tg.escrow.moderation.WarningPort noWarnings = new com.tg.escrow.moderation.WarningPort() {
            @Override
            public int warn(long guildId, long userId) {
                return 1;
            }

            @Override
            public int countOf(long guildId, long userId) {
                return 0;
            }

            @Override
            public void clear(long guildId, long userId) {
            }
        };
        return new ModerationCommandHandler(orch, (chatId, userId) -> MemberRole.MEMBER, noWarnings,
                new com.tg.escrow.core.WarningOrchestrator(
                        new com.tg.escrow.core.WarningPolicy(3, 5), orch, java.time.Duration.ofMinutes(10)));
    }

    private static BotDispatcher dispatcher(BannedWordRegistry registry) {
        return new BotDispatcher(tradeHandler(), "mybot", registry, new KeywordAutoReply(), noopModeration());
    }

    private static BotDispatcher dispatcher(KeywordAutoReply autoReply) {
        return new BotDispatcher(tradeHandler(), "mybot", new BannedWordRegistry(), autoReply, noopModeration());
    }

    private static BotDispatcher dispatcher() {
        return dispatcher(new BannedWordRegistry());
    }

    @Test
    @DisplayName("自动回复：普通消息命中关键词 → 返回预设回复（KeywordAutoReply 的生产消费者）")
    void autoReplyHit() {
        KeywordAutoReply autoReply = new KeywordAutoReply();
        autoReply.register("怎么收费", "平台费默认 0%。");

        BotReply reply = dispatcher(autoReply).handle(100L, "请问 怎么收费", ACTOR);

        assertThat(reply.text()).isEqualTo("平台费默认 0%。");
        assertThat(reply.offerWebApp()).isFalse();
    }

    @Test
    @DisplayName("违禁词优先于自动回复（同时命中给处置回执）")
    void bannedWordBeatsAutoReply() {
        KeywordAutoReply autoReply = new KeywordAutoReply();
        autoReply.register("广告词", "自动回复");
        BannedWordRegistry registry = new BannedWordRegistry();
        registry.reload(100L, List.of("广告词"), List.of());

        BotDispatcher d = new BotDispatcher(tradeHandler(), "mybot", registry, autoReply, noopModeration());

        BotReply reply = d.handle(100L, "看这个 广告词", ACTOR);

        assertThat(reply.text()).contains("违禁");
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

        BotReply reply = dispatcher(registry).handle(100L, "这里有 赌博广告 快来", ACTOR);

        assertThat(reply.text()).contains("违禁").contains("赌博广告");
    }

    @Test
    @DisplayName("违禁词按群隔离：A 群的词在 B 群不触发")
    void bannedWordPerGuild() {
        BannedWordRegistry registry = new BannedWordRegistry();
        registry.reload(100L, List.of("赌博广告"), List.of());

        assertThat(dispatcher(registry).handle(200L, "这里有 赌博广告", ACTOR)).isNull();
    }

    @Test
    @DisplayName("escrow 命令 → 路由到交易处理器：先 create 预览、再 confirm，回执含订单号")
    void escrowCommandRouted() {
        BotDispatcher d = dispatcher();

        d.handle(100L, "/escrow create 2002 100 USDT", ACTOR);          // ET-34 必经的预览
        BotReply reply = d.handle(100L, "/escrow confirm 2002 100 USDT", ACTOR);

        assertThat(reply.text()).contains("已创建订单");
        assertThat(reply.offerWebApp()).isFalse();   // 正常落单回执不带表单按钮
    }

    @Test
    @DisplayName("裸 /escrow（无子命令）→ 用法回执且引导表单（Wave 3：用户不必记命令语法）")
    void bareEscrowOffersWebAppForm() {
        BotReply reply = dispatcher().handle(100L, "/escrow", ACTOR);

        assertThat(reply.text()).isEqualTo(TradeCommandHandler.USAGE);
        assertThat(reply.offerWebApp()).isTrue();
    }

    @Test
    @DisplayName("其它命令 → 帮助回执（含用法）")
    void unknownCommandGetsHelp() {
        BotReply reply = dispatcher().handle(100L, "/whatever", ACTOR);

        assertThat(reply.text()).contains("escrow").contains("用法");
        assertThat(reply.offerWebApp()).isTrue();   // 帮助场景引导去表单
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
