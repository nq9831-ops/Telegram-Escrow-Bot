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
import com.tg.escrow.core.KeywordAutoReply;
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
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
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

        @Override
        public void sendText(long chatId, String text) {
            texts.add(text);
        }

        @Override
        public void sendTextWithWebApp(long chatId, String text, String buttonText, String url) {
            webAppSends.add(text + "|" + url);
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

    private static final String WEBAPP_URL = "https://example.test/miniapp/index.html";

    private static TelegramBotHandler handler(RecordingReply reply, InMemoryStore store) {
        return handler(reply, store, WEBAPP_URL);
    }

    private static TelegramBotHandler handler(RecordingReply reply, InMemoryStore store, String webAppUrl) {
        Clock clock = Clock.fixed(T0, ZoneOffset.UTC);
        TradeAdmissionGate gate = new TradeAdmissionGate(new TradeAdmissionPolicy(5, Duration.ZERO));
        TradeHistoryPort history = id -> new TradeAdmissionContext(id, 0, null, false);
        TradeCommandHandler trade = new TradeCommandHandler(
                new EscrowTradeService(gate, history, store, clock),
                new AmountTierPolicy(new BigDecimal("100"), new BigDecimal("1000")),
                new PendingTradeRegistry(Duration.ofMinutes(10), clock),
                store);
        BotDispatcher dispatcher = new BotDispatcher(trade, "mybot",
                new BannedWordRegistry(), new KeywordAutoReply());
        return new TelegramBotHandler(BotTokenConfig.from(k -> "123456:TESTTOKEN"), dispatcher,
                reply, "mybot", webAppUrl);
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
}
