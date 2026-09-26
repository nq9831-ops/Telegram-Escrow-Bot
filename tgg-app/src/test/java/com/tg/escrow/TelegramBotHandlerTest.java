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
import com.tg.escrow.core.ChatKind;
import com.tg.escrow.core.IncomingMessage;
import com.tg.escrow.core.KeywordAutoReply;
import com.tg.escrow.core.ModerationOrchestrator;
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
import com.tg.escrow.escrow.TradeInvite;
import com.tg.escrow.escrow.TradeInviteService;
import com.tg.escrow.escrow.TradeInviteStore;
import com.tg.escrow.escrow.TradeMaintenanceService;
import com.tg.escrow.escrow.TradeReviewService;
import com.tg.escrow.escrow.TradeReviewStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Document;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Telegram 胶水层（{@code TelegramBotHandler}）的行为固定测试——此前**整层无测试**。
 *
 * <h2>它补的是什么</h2>
 * <p>命令层（{@code TradeCommandHandler}）已有测试，但"Update 进来 → 回执发出去"这一段
 * 只在真实 Telegram 里被验过——而那需要线上环境。本类用构造的 {@link Update} 把这段钉住：
 * 文本消息的路由、以及 <b>callback 必应答</b>的不变量（不答会让客户端按钮一直转圈）。
 *
 * <p>注意 {@code /escrow status} 与 {@code /escrow cancel} 在命令层走的是<b>不同分支</b>，
 * 所以线上验过 cancel 不等于验过 status——这也正是本类存在的理由。
 */
class TelegramBotHandlerTest {

    private static final Instant T0 = Instant.parse("2026-09-23T00:00:00Z");

    /** 记录回执与应答，替代真实 Telegram 出口。 */
    private static final class RecordingReply implements BotReplyPort {
        final List<String> texts = new ArrayList<>();
        final List<String> acks = new ArrayList<>();
        /** 每次带按钮的发送记一条 "text|url"。 */
        final List<String> webAppSends = new ArrayList<>();

        /** 带通知策略的发送：记 "chatId|silent|loud|text"——便于断言「发给谁 + 是否静默」。 */
        final List<String> policySends = new ArrayList<>();

        @Override
        public void sendText(long chatId, String text) {
            texts.add(text);
        }

        @Override
        public void sendTextWithWebApp(long chatId, String text, String buttonText, String url) {
            webAppSends.add(text + "|" + url);
        }

        @Override
        public void sendText(long chatId, String text, com.tg.escrow.core.NoticePolicy policy) {
            policySends.add(chatId + "|" + (policy.silent() ? "silent" : "loud") + "|" + text);
        }

        @Override
        public void ackCallback(String callbackQueryId) {
            acks.add(callbackQueryId);
        }
    }

    /** 内存订单存储 + 查询端口。 */
    private static final class InMemoryStore implements EscrowOrderStore, EscrowOrderLookupPort {
        private final Map<Long, EscrowOrder> byId = new HashMap<>();
        private long seq;

        @Override
        public EscrowOrder save(EscrowOrder order) {
            order.assignId(++seq);
            byId.put(order.getId(), order);
            return order;
        }

        @Override
        public Optional<EscrowOrder> byId(long orderId) {
            return Optional.ofNullable(byId.get(orderId));
        }
    }

    /** 本类不测邀请路径——给个空实现让装配成形即可。 */
    private static final class NoopInviteStore implements TradeInviteStore {
        @Override
        public TradeInvite save(TradeInvite invite) {
            return invite;
        }

        @Override
        public Optional<TradeInvite> byToken(String token) {
            return Optional.empty();
        }
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

    /** 本类不测评价路径——给个空实现让装配成形即可。 */
    private static TradeReviewService noopReviewService(Clock clock) {
        return new TradeReviewService(new TradeReviewStore() {
            @Override
            public void record(long orderId, long reviewerId, int score, java.time.Instant createdAt) {
            }

            @Override
            public java.util.Set<Long> reviewersOf(long orderId) {
                return java.util.Set.of();
            }
        }, clock);
    }

    private static final String WEBAPP_URL = "https://example.test/miniapp/index.html";

    private static TelegramBotHandler handler(RecordingReply reply, InMemoryStore store) {
        return handler(reply, store, WEBAPP_URL);
    }

    private static TelegramBotHandler handler(RecordingReply reply, InMemoryStore store, String webAppUrl) {
        return handler(reply, store, webAppUrl, new com.tg.escrow.core.ProtectionMode(),
                new RecordingAdmin());
    }

    /** 变体：允许指定保护模式与管理动作端口（验收「保护模式真的拦人」需要它们）。 */
    private static TelegramBotHandler handler(RecordingReply reply, InMemoryStore store, String webAppUrl,
                                              com.tg.escrow.core.ProtectionMode protection,
                                              RecordingAdmin admin) {
        Clock clock = Clock.fixed(T0, ZoneOffset.UTC);
        TradeAdmissionGate gate = new TradeAdmissionGate(new TradeAdmissionPolicy(5, Duration.ZERO));
        TradeHistoryPort history = id -> new TradeAdmissionContext(id, 0, null, false);
        TradeCommandHandler trade = new TradeCommandHandler(
                new EscrowTradeService(gate, history, store, clock),
                new AmountTierPolicy(new BigDecimal("100"), new BigDecimal("1000")),
                new PendingTradeRegistry(Duration.ofMinutes(10), clock),
                store,
                new TradeInviteService(gate, history, new NoopInviteStore(), store,
                        Duration.ofHours(24), () -> "tok0", clock),
                new InviteLink("mybot"),
                noopReviewService(clock),
                maintenanceService(clock),
                new TradeNotifier(reply, clock, null));
        ModerationOrchestrator orch = new ModerationOrchestrator(new com.tg.escrow.core.GroupAdminPort() {
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
        BotDispatcher dispatcher = new BotDispatcher(trade, "mybot",
                new BannedWordRegistry(), new KeywordAutoReply(),
                new ModerationCommandHandler(orch,
                        (chatId, userId) -> com.tg.escrow.core.MemberRole.MEMBER, noWarnings,
                        new com.tg.escrow.core.WarningOrchestrator(
                                new com.tg.escrow.core.WarningPolicy(3, 5), orch,
                                java.time.Duration.ofMinutes(10))),
                new MessageGuardService(
                        new com.tg.escrow.core.MessageGuardOrchestrator(
                                new com.tg.escrow.core.LinkFilter(java.util.List.of(), java.util.List.of()),
                                new com.tg.escrow.core.MediaFilter(java.util.Set.of(), java.util.Set.of()),
                                new com.tg.escrow.core.RateLimiter(new com.tg.escrow.core.RateLimitPolicy(
                                        java.time.Duration.ofMinutes(1), 1000, 1000, 1000)),
                                java.time.Clock.fixed(java.time.Instant.parse("2026-09-24T10:00:00Z"),
                                        java.time.ZoneOffset.UTC)),
                        new com.tg.escrow.core.GroupAdminPort() {
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
                        },
                        noWarnings));
        return new TelegramBotHandler(BotTokenConfig.from(k -> "123456:TESTTOKEN"), dispatcher,
                reply, "mybot", webAppUrl,
                (chatId, userId) -> com.tg.escrow.core.MemberRole.MEMBER,
                new JoinSubscriptionGate(
                        new com.tg.escrow.core.ChannelSubscriptionCheck(java.util.Set.of()),
                        (channel, userId) -> com.tg.escrow.core.ChannelMembershipPort.Membership.UNKNOWN,
                        new com.tg.escrow.core.GroupAdminPort() {
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
                        },
                        new MemberJoinHandler(protection,
                                new com.tg.escrow.core.WelcomeTemplate("欢迎 {username}"), admin),
                        // 必订频道留空：本类测的是入群文案与保护模式，订阅门禁由 JoinSubscriptionGateTest 覆盖
                        java.util.Set.of()));
    }

    private static Update textUpdate(long chatId, long userId, String text) {
        Message message = new Message();
        message.setMessageId(1);
        message.setChat(new Chat(chatId, "private"));
        User from = new User(userId, "tester", false);
        message.setFrom(from);
        message.setText(text);
        Update update = new Update();
        update.setMessage(message);
        return update;
    }

    private static Update callbackUpdate(String callbackId) {
        CallbackQuery query = new CallbackQuery();
        query.setId(callbackId);
        Update update = new Update();
        update.setCallbackQuery(query);
        return update;
    }

    @Test
    @DisplayName("文本消息 → 路由到命令层 → 回执真的发出（sendText 被调用）")
    void textMessageProducesReply() {
        RecordingReply reply = new RecordingReply();
        InMemoryStore store = new InMemoryStore();
        TelegramBotHandler h = handler(reply, store);

        // 建一笔订单，再用 status 查它——覆盖 handleStatus 分支（与 cancel 不同分支）
        h.consume(textUpdate(100L, 4242L, "/escrow create 2002 100 USDT"));
        h.consume(textUpdate(100L, 4242L, "/escrow confirm 2002 100 USDT"));
        reply.texts.clear();

        h.consume(textUpdate(100L, 4242L, "/escrow status 1"));

        assertThat(reply.texts).hasSize(1);
        assertThat(reply.texts.get(0)).contains("#1").contains("订单已创建");
    }

    @Test
    @DisplayName("cancel 经胶水层：回执已取消，且状态真落库（不同于 status 分支）")
    void cancelThroughGlueLayer() {
        RecordingReply reply = new RecordingReply();
        InMemoryStore store = new InMemoryStore();
        TelegramBotHandler h = handler(reply, store);
        h.consume(textUpdate(100L, 4242L, "/escrow create 2002 100 USDT"));
        h.consume(textUpdate(100L, 4242L, "/escrow confirm 2002 100 USDT"));

        h.consume(textUpdate(100L, 4242L, "/escrow cancel 1"));

        assertThat(reply.texts.get(reply.texts.size() - 1)).contains("已取消");
        assertThat(store.byId(1L).orElseThrow().currentState())
                .isEqualTo(EscrowOrder.State.CANCELLED);
    }

    @Test
    @DisplayName("callback 必应答：即使 update 里没有消息，也必须 ack（否则按钮一直转圈）")
    void callbackAlwaysAcked() {
        RecordingReply reply = new RecordingReply();
        TelegramBotHandler h = handler(reply, new InMemoryStore());

        h.consume(callbackUpdate("cb-1"));

        assertThat(reply.acks).containsExactly("cb-1");
    }

    @Test
    @DisplayName("裸 /escrow（无子命令）→ 走带 WebApp 按钮的出口（sendTextWithWebApp 被调用）")
    void bareEscrowSendsWebAppButton() {
        RecordingReply reply = new RecordingReply();
        TelegramBotHandler h = handler(reply, new InMemoryStore());

        h.consume(textUpdate(100L, 4242L, "/escrow"));

        assertThat(reply.webAppSends).hasSize(1);
        assertThat(reply.webAppSends.get(0)).endsWith("|" + WEBAPP_URL);
        assertThat(reply.texts).isEmpty();   // 该路径不走纯文本出口
    }

    @Test
    @DisplayName("未配置表单 URL → 同一路径退化为纯文本（不抛、不带按钮）")
    void bareEscrowFallsBackToPlainWhenNoUrl() {
        RecordingReply reply = new RecordingReply();
        TelegramBotHandler h = handler(reply, new InMemoryStore(), "");

        h.consume(textUpdate(100L, 4242L, "/escrow"));

        assertThat(reply.webAppSends).isEmpty();
        assertThat(reply.texts).hasSize(1);
    }

    @Test
    @DisplayName("null update → 不抛、不响应（群里什么都有）")
    void nullUpdateIgnored() {
        RecordingReply reply = new RecordingReply();
        TelegramBotHandler h = handler(reply, new InMemoryStore());

        h.consume((Update) null);

        assertThat(reply.texts).isEmpty();
        assertThat(reply.acks).isEmpty();
    }

    // ── 入站消息映射（Wave 2）──────────────────────────────────────────────

    private static Message rawMessage(long chatId, long userId, int messageId, String text) {
        Message message = new Message();
        message.setMessageId(messageId);
        message.setChat(new Chat(chatId, "supergroup"));
        message.setFrom(new User(userId, "tester", false));
        message.setText(text);
        return message;
    }

    @Test
    @DisplayName("文本消息 → 映射保真（群/用户/消息号/文本），媒体为 NONE")
    void mapsTextMessage() {
        IncomingMessage mapped = TelegramBotHandler.toIncoming(rawMessage(100L, 4242L, 77, "你好"));

        assertThat(mapped.chatId()).isEqualTo(100L);
        assertThat(mapped.userId()).isEqualTo(4242L);
        assertThat(mapped.messageId()).isEqualTo(77);
        assertThat(mapped.text()).isEqualTo("你好");
        assertThat(mapped.mediaKind()).isEqualTo(IncomingMessage.MediaKind.NONE);
        assertThat(mapped.hasMedia()).isFalse();
    }

    @Test
    @DisplayName("聊天类型映射：私人/群/超级群 → 对应枚举；内容安全只对群生效")
    void mapsChatKind() {
        assertThat(TelegramBotHandler.toIncoming(rawChat(1L, "private")).chatKind())
                .isEqualTo(ChatKind.PRIVATE);
        assertThat(TelegramBotHandler.toIncoming(rawChat(1L, "group")).chatKind())
                .isEqualTo(ChatKind.GROUP);
        assertThat(TelegramBotHandler.toIncoming(rawChat(1L, "supergroup")).chatKind())
                .isEqualTo(ChatKind.SUPERGROUP);
        // 无法识别 → CHANNEL（不适用内容安全：无法确认是群就不删消息）
        assertThat(TelegramBotHandler.toIncoming(rawChat(1L, "whatever")).chatKind())
                .isEqualTo(ChatKind.CHANNEL);
        assertThat(ChatKind.PRIVATE.moderatable()).isFalse();
        assertThat(ChatKind.SUPERGROUP.moderatable()).isTrue();
    }

    private static Message rawChat(long chatId, String type) {
        Message message = new Message();
        message.setMessageId(1);
        message.setChat(new Chat(chatId, type));
        message.setFrom(new User(4242L, "tester", false));
        message.setText("hi");
        return message;
    }

    @Test
    @DisplayName("畸形输入：newChatMembers 混入 null → 不炸，且其余成员的欢迎语照发")
    void nullNewMemberDoesNotBreakTheWholeEvent() {
        RecordingReply reply = new RecordingReply();
        TelegramBotHandler h = handler(reply, new InMemoryStore());
        Message message = new Message();
        message.setMessageId(9);
        message.setChat(new Chat(100L, "supergroup"));
        // 用 Arrays.asList（允许 null）而非 List.of（不允许）——本用例要构造的正是畸形输入
        message.setNewChatMembers(java.util.Arrays.asList(null, new User(8888L, "小明", false)));
        Update update = new Update();
        update.setMessage(message);

        h.consume(update);

        assertThat(reply.texts)
                .as("一条 null 不该让整个入群事件挂掉、连带丢掉其余成员的欢迎语")
                .hasSize(1);
        assertThat(reply.texts.get(0)).contains("小明");
    }

    /** 记录管理动作；{@code failKick} 置位则抛（模拟操作失败）。 */
    private static final class RecordingAdmin implements com.tg.escrow.core.GroupAdminPort {
        final List<Long> kicked = new ArrayList<>();
        boolean failKick;

        @Override
        public void kick(long guildId, long userId) {
            if (failKick) {
                throw new com.tg.escrow.common.TggException("模拟踢人失败");
            }
            kicked.add(userId);
        }

        @Override
        public void ban(long guildId, long userId) {
            throw new UnsupportedOperationException("本类不测封禁");
        }

        @Override
        public void mute(long guildId, long userId, java.time.Duration duration) {
            throw new UnsupportedOperationException("本类不测禁言");
        }

        @Override
        public void deleteMessage(long guildId, long messageId) {
            throw new UnsupportedOperationException("本类不测删消息");
        }
    }

    @Test
    @DisplayName("【验收】保护模式开启时拉人 → 该成员被真正移出，且群里不出现欢迎语")
    void acceptanceProtectedJoinActuallyRemovesNewMember() {
        RecordingReply reply = new RecordingReply();
        RecordingAdmin admin = new RecordingAdmin();
        com.tg.escrow.core.ProtectionMode protection = new com.tg.escrow.core.ProtectionMode();
        protection.enable("疑似批量拉人");
        TelegramBotHandler h = handler(reply, new InMemoryStore(), WEBAPP_URL, protection, admin);

        h.consume(memberJoinUpdate(100L, 7777L, "小明"));

        assertThat(admin.kicked)
                .as("提示写着『暂不接受新成员』，就必须真的把 7777 移出——这是本任务的可观察结果")
                .containsExactly(7777L);
        assertThat(reply.texts)
                .as("拦截生效时只发拦截说明，不发欢迎语")
                .hasSize(1);
        assertThat(reply.texts.get(0)).contains("保护模式").doesNotContain("欢迎");
    }

    // ── 入群事件（Wave 2）──────────────────────────────────────────────────

    private static Update memberJoinUpdate(long chatId, long newMemberId, String name) {
        Message message = new Message();
        message.setMessageId(9);
        message.setChat(new Chat(chatId, "supergroup"));
        message.setFrom(new User(newMemberId, name, false));
        message.setNewChatMembers(List.of(new User(newMemberId, name, false)));
        Update update = new Update();
        update.setMessage(message);
        return update;
    }

    @Test
    @DisplayName("【接线证据】新成员入群 → 经胶水层真的发出欢迎语（此前 new_chat_members 整条被跳过）")
    void memberJoinSendsWelcome() {
        RecordingReply reply = new RecordingReply();
        TelegramBotHandler h = handler(reply, new InMemoryStore());

        h.consume(memberJoinUpdate(100L, 7777L, "小明"));

        assertThat(reply.texts)
                .as("门禁若不认 new_chat_members，这里会是空——欢迎语永远不会发出")
                .hasSize(1);
        assertThat(reply.texts.get(0)).contains("欢迎").contains("小明");
    }

    @Test
    @DisplayName("bot 自己入群 → 不自我欢迎")
    void botJoiningDoesNotWelcomeItself() {
        RecordingReply reply = new RecordingReply();
        TelegramBotHandler h = handler(reply, new InMemoryStore());
        Message message = new Message();
        message.setMessageId(9);
        message.setChat(new Chat(100L, "supergroup"));
        message.setNewChatMembers(List.of(new User(5555L, "otherbot", true)));
        Update update = new Update();
        update.setMessage(message);

        h.consume(update);

        assertThat(reply.texts).isEmpty();
    }

    @Test
    @DisplayName("文档消息 → DOCUMENT 且带出文件名与 MIME（媒体过滤的输入）")
    void mapsDocumentMessage() {
        Message message = rawMessage(100L, 4242L, 78, null);
        Document document = new Document();
        document.setFileName("payload.exe");
        document.setMimeType("application/octet-stream");
        message.setDocument(document);

        IncomingMessage mapped = TelegramBotHandler.toIncoming(message);

        assertThat(mapped.mediaKind()).isEqualTo(IncomingMessage.MediaKind.DOCUMENT);
        assertThat(mapped.fileName()).isEqualTo("payload.exe");
        assertThat(mapped.mimeType()).isEqualTo("application/octet-stream");
        assertThat(mapped.hasFileInfo()).isTrue();
        assertThat(mapped.text()).isNull();
    }

    @Test
    @DisplayName("图片消息 → PHOTO，且无文件信息（不得拿空值当违规）")
    void mapsPhotoMessage() {
        Message message = rawMessage(100L, 4242L, 79, null);
        message.setPhoto(java.util.List.of(
                org.mockito.Mockito.mock(org.telegram.telegrambots.meta.api.objects.photo.PhotoSize.class)));

        IncomingMessage mapped = TelegramBotHandler.toIncoming(message);

        assertThat(mapped.mediaKind()).isEqualTo(IncomingMessage.MediaKind.PHOTO);
        assertThat(mapped.hasFileInfo()).isFalse();
    }
}
