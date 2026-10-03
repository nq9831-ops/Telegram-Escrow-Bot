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

import com.tg.escrow.core.ChatKind;
import com.tg.escrow.core.IncomingMessage;
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
        public void sendTextWithButtons(long chatId, String text, List<BotReply.ActionButton> buttons,
                                        com.tg.escrow.core.NoticePolicy policy) {
            // 本替身不区分通知策略（策略语义由 TradeNotifierTest 覆盖）
            sendTextWithButtons(chatId, text, buttons);
        }

        @Override
        public void sendTextWithWebApp(long chatId, String text, String buttonText, String url) {
            webAppSends.add(text + "|" + url);
        }

        /** 每次带动作按钮的发送记 "text|callbackData1,callbackData2"。 */
        final List<String> buttonSends = new ArrayList<>();

        @Override
        public void sendTextWithButtons(long chatId, String text, List<BotReply.ActionButton> buttons) {
            // 文本同样记进 texts：带按钮只是出口不同，"回执有没有发出去"的断言仍然成立
            texts.add(text);
            buttonSends.add(text + "|" + buttons.stream()
                    .map(BotReply.ActionButton::callbackData)
                    .collect(java.util.stream.Collectors.joining(",")));
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
        Clock clock = Clock.fixed(T0, ZoneOffset.UTC);
        TradeAdmissionGate gate = new TradeAdmissionGate(new TradeAdmissionPolicy(5, Duration.ZERO));
        TradeHistoryPort history = id -> new TradeAdmissionContext(id, 0, null, false);
        TradeCommandHandler trade = new TradeCommandHandler(
                new EscrowTradeService(gate, history, store, AllowedCurrencies.all(), clock),
                new AmountTierPolicy(new BigDecimal("100"), new BigDecimal("1000")),
                new PendingTradeRegistry(Duration.ofMinutes(10), clock),
                store,
                new TradeInviteService(gate, history, new NoopInviteStore(), store,
                        Duration.ofHours(24), () -> "tok0", AllowedCurrencies.all(), clock),
                new InviteLink("mybot"),
                noopReviewService(clock),
                maintenanceService(clock),
                new TradeNotifier(reply, clock, null,
                         new com.tg.escrow.core.UserIdMasker("test-salt")),
                StubTraderStats.EMPTY, new com.tg.escrow.escrow.TraderCreditService(),
                AllowedCurrencies.all(), new com.tg.escrow.core.UserIdMasker("test-salt"),
                StubTradeGroup.service(clock), StubTradeGroup.port(), com.tg.escrow.escrow.FeePolicy.zero(), StubFraudLinkage.service(), StubDispute.service(clock),
                new com.tg.escrow.escrow.ConfirmGate(Duration.ofMinutes(5), clock));
        BotDispatcher dispatcher = new BotDispatcher(trade, "mybot");
        return new TelegramBotHandler(BotTokenConfig.from(k -> "123456:TESTTOKEN"), dispatcher,
                reply, "mybot", webAppUrl,
                (chatId, userId) -> com.tg.escrow.core.MemberRole.MEMBER,
                null);
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

    /**
     * 带 from / 所在消息 / data 的 callback（真实按钮点击的形态）。
     *
     * <p>与上面的极简版不同：执行动作需要这三样——{@code from} 决定「以谁的身份执行」
     * （权限守卫据此判定），所在消息决定回执发回哪个 chat，{@code data} 才是动作编码。
     */
    private static Update callbackUpdate(String callbackId, long chatId, long userId, String data) {
        Message message = new Message();
        message.setMessageId(7);
        message.setChat(new Chat(chatId, "private"));
        CallbackQuery query = new CallbackQuery();
        query.setId(callbackId);
        query.setFrom(new User(userId, "tester", false));
        query.setMessage(message);
        query.setData(data);
        Update update = new Update();
        update.setCallbackQuery(query);
        return update;
    }

    @Test
    @DisplayName("点动作按钮 → 解码成等价命令并真的执行；越权仍被守卫拒绝（按钮 ≠ 授权）")
    void callbackExecutesEquivalentCommandAndStillEnforcesGuards() {
        RecordingReply reply = new RecordingReply();
        TelegramBotHandler h = handler(reply, new InMemoryStore());

        // 4242（买方）建单并托管
        h.consume(textUpdate(100L, 4242L, "/escrow create 2002 100 USDT"));
        h.consume(textUpdate(100L, 4242L, "/escrow confirm 2002 100 USDT"));
        h.consume(textUpdate(100L, 4242L, "/escrow lock 1"));

        // 卖方 2002 点「交付」按钮（dl:1）→ 等价于 /escrow deliver 1，应真的交付
        reply.texts.clear();
        h.consume(callbackUpdate("cb-1", 100L, 2002L, "dl:1"));
        assertThat(reply.texts).as("按钮点击应产生与命令相同的回执")
                .anyMatch(t -> t.contains("已交付"));
        assertThat(reply.acks).as("每个 callback 必应答").contains("cb-1");

        // 买方 4242 也点「交付」按钮 → 角色不符，守卫必须照旧拒绝
        reply.texts.clear();
        h.consume(callbackUpdate("cb-2", 100L, 4242L, "dl:1"));
        assertThat(reply.texts).as("按钮不是授权：仅卖方可交付")
                .anyMatch(t -> t.contains("无法交付"));
        assertThat(reply.acks).contains("cb-2");
    }

    @Test
    @DisplayName("未知/畸形的 callback data → 只应答，不执行任何动作（不派出回执）")
    void unknownCallbackDataOnlyAcks() {
        RecordingReply reply = new RecordingReply();
        TelegramBotHandler h = handler(reply, new InMemoryStore());

        h.consume(callbackUpdate("cb-9", 100L, 4242L, "garbage"));

        assertThat(reply.texts).as("畸形数据不得触发任何命令").isEmpty();
        assertThat(reply.acks).as("但必须应答（否则按钮一直转圈）").contains("cb-9");
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

    @Test
    @DisplayName("回复目标映射：回复某人 → 记下被回复者与消息号；回复 bot 的消息 → 不把 bot 当人")
    void mapsReplyTarget() {
        Message message = rawChat(-100123L, "supergroup");
        Message replyTo = new Message();
        replyTo.setMessageId(41);
        replyTo.setFrom(new User(3003L, "target", false));
        message.setReplyToMessage(replyTo);

        IncomingMessage mapped = TelegramBotHandler.toIncoming(message);
        assertThat(mapped.replyToUserId()).isEqualTo(3003L);
        assertThat(mapped.replyToMessageId()).isEqualTo(41L);
        assertThat(mapped.hasReplyUser()).isTrue();

        Message botReply = new Message();
        botReply.setMessageId(42);
        botReply.setFrom(new User(999L, "the_bot", true));
        message.setReplyToMessage(botReply);
        IncomingMessage mappedBot = TelegramBotHandler.toIncoming(message);
        assertThat(mappedBot.replyToUserId()).as("bot 不是可操作对象，不得成为目标").isZero();
        assertThat(mappedBot.replyToMessageId()).as("消息号仍记录（/del 可用）").isEqualTo(42L);
    }

    private static Message rawChat(long chatId, String type) {
        Message message = new Message();
        message.setMessageId(1);
        message.setChat(new Chat(chatId, type));
        message.setFrom(new User(4242L, "tester", false));
        message.setText("hi");
        return message;
    }

    /** 群内**以频道身份**发的帖：{@code senderChat} 有值、{@code from} 刻意不设。 */
    private static Message rawChannelPost(long chatId, int messageId, String channelTitle, String text) {
        Message message = new Message();
        message.setMessageId(messageId);
        message.setChat(new Chat(chatId, "supergroup"));
        Chat senderChannel = new Chat(-1009999999999L, "channel");
        senderChannel.setTitle(channelTitle);
        message.setSenderChat(senderChannel);
        message.setText(text);
        return message;
    }

    @Test
    @DisplayName("【缺口复现】频道帖（无 from、有 senderChat）→ 不再抛异常，身份被如实承载")
    void channelPostIsMappedInsteadOfThrowing() {
        IncomingMessage mapped = TelegramBotHandler.toIncoming(
                rawChannelPost(100L, 77, "RogueChannel", "看这个 http://evil.example/x"));

        assertThat(mapped.senderIsChannel()).isTrue();
        assertThat(mapped.senderName()).isEqualTo("RogueChannel");
        assertThat(mapped.userId()).as("频道帖没有个人发送者").isZero();
        assertThat(mapped.chatKind())
                .as("帖发在群里，内容安全依然适用（不因发送者是频道而免检）")
                .isEqualTo(ChatKind.SUPERGROUP);
    }

    @Test
    @DisplayName("频道名取值：优先 @username，退回 title（守卫按名字匹配白名单）")
    void channelNamePrefersUsername() {
        Message message = rawChannelPost(100L, 77, "Rogue Channel", "hi");
        message.getSenderChat().setUserName("rogue_channel");

        assertThat(TelegramBotHandler.toIncoming(message).senderName()).isEqualTo("rogue_channel");
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
