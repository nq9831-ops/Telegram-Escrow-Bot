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
package com.tg.escrow.webapp;

import com.tg.escrow.BotReplyPort;
import com.tg.escrow.TradeNotifier;

import com.tg.escrow.TradeCommandHandler;
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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mini App 交易 API 的行为固定测试。
 *
 * <h2>它守的是什么</h2>
 * <p>「验签不通过 → 不执行」是这套 API 的**唯一身份防线**：Mini App 的调用者不经 Telegram
 * 命令通道，请求体里的 {@code initData} 是唯一的身份来源。因此本类把**拒绝路径**逐个钉死
 * （缺 initData / 验签失败 / 参数非法），并断言"拒绝时绝不能落单"。
 */
class TradeApiControllerTest {

    private static final String BOT_TOKEN = "123456:TEST-TOKEN-abc";
    private static final Instant T0 = Instant.parse("2026-09-24T12:00:00Z");
    private static final long USER_ID = 8724975623L;

    /** 内存存储 + 查询。 */
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

    private static String sign(String dcs) {
        try {
            Mac outer = Mac.getInstance("HmacSHA256");
            outer.init(new SecretKeySpec("WebAppData".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] secret = outer.doFinal(BOT_TOKEN.getBytes(StandardCharsets.UTF_8));
            Mac inner = Mac.getInstance("HmacSHA256");
            inner.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(inner.doFinal(dcs.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String initData(long userId) {
        List<String> fields = new ArrayList<>(List.of(
                "auth_date=" + T0.getEpochSecond(),
                "user=" + URLEncoder.encode("{\"id\":" + userId + "}", StandardCharsets.UTF_8)));
        fields.sort(String::compareTo);
        return String.join("&", fields) + "&hash=" + sign(String.join("\n", fields));
    }

    /** 记录主动通知：{@code chatId|text}——用于断言"落单后告诉了谁"。 */
    private static final class RecordingNotifications implements BotReplyPort {
        final java.util.List<String> sent = new java.util.ArrayList<>();

        @Override
        public void sendText(long chatId, String text) {
            sent.add(chatId + "|" + text);
        }

        /** 一次性开关：置位后下一次发送失败（模拟对方未与机器人会话 / 被拉黑）。 */
        boolean failNextSend;

        @Override
        public void sendText(long chatId, String text, com.tg.escrow.core.NoticePolicy policy) {
            if (failNextSend) {
                failNextSend = false;
                throw new com.tg.escrow.common.TggException("模拟发送失败");
            }
            sent.add(chatId + "|" + text);
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

    @Test
    @DisplayName("落单后主动通知卖方，且响应回 notified=true（表单侧也能知道对方是否可达）")
    void notifiesCounterpartyAndReportsIt() {
        InMemoryStore store = new InMemoryStore();
        RecordingNotifications notifications = new RecordingNotifications();

        Map<String, Object> body = controller(store, notifications).create(
                new TradeApiController.CreateRequest(initData(USER_ID), 2002L, "100", "USDT"));

        assertThat(body).containsEntry("ok", true).containsEntry("notified", true);
        assertThat(notifications.sent).anySatisfy(s -> assertThat(s).startsWith("2002|"));
    }

    @Test
    @DisplayName("通知发不出去 → 落单仍成功，但 notified=false（不谎报已通知对方）")
    void reportsNotifiedFalseWhenCounterpartyUnreachable() {
        InMemoryStore store = new InMemoryStore();
        RecordingNotifications notifications = new RecordingNotifications();
        notifications.failNextSend = true;

        Map<String, Object> body = controller(store, notifications).create(
                new TradeApiController.CreateRequest(initData(USER_ID), 2002L, "100", "USDT"));

        assertThat(body).containsEntry("ok", true).containsEntry("notified", false);
        assertThat(notifications.sent).as("发送失败时不该留下任何已发送记录").isEmpty();
    }

    private static TradeApiController controller(InMemoryStore store) {
        return controller(store, new RecordingNotifications());
    }

    private static TradeApiController controller(InMemoryStore store,
                                                 RecordingNotifications notifications) {
        Clock clock = Clock.fixed(T0, ZoneOffset.UTC);
        TradeAdmissionGate gate = new TradeAdmissionGate(new TradeAdmissionPolicy(5, Duration.ZERO));
        TradeHistoryPort history = id -> new TradeAdmissionContext(id, 0, null, false);
        // 直接用 EscrowTradeService 而非 TradeCommandHandler：后者返回 String 回执，
        // API 层需要结构化 orderId，解析文案既脆又会在文案改动时静默失效。
        EscrowTradeService service = new EscrowTradeService(gate, history, store, clock);
        return new TradeApiController(
                new WebAppInitDataVerifier(BOT_TOKEN, Duration.ofHours(1), clock),
                service,
                new AmountTierPolicy(new BigDecimal("100"), new BigDecimal("1000")),
                new TradeNotifier(notifications, clock, null));
    }

    @Test
    @DisplayName("验签通过 + 参数合法 → 落单成功，返回订单号与风险提示")
    void happyPathCreatesOrder() {
        InMemoryStore store = new InMemoryStore();

        Map<String, Object> body = controller(store).create(
                new TradeApiController.CreateRequest(initData(USER_ID), 2002L, "100", "USDT"));

        assertThat(body).containsEntry("ok", true);
        assertThat(body.get("orderId")).isNotNull();
        assertThat(store.byId(((Number) body.get("orderId")).longValue())).isPresent();
    }

    @Test
    @DisplayName("缺 initData / 验签失败 → 401 且【不落单】（身份防线）")
    void missingOrBadInitDataRejected() {
        InMemoryStore store = new InMemoryStore();
        TradeApiController c = controller(store);

        assertThat(c.create(new TradeApiController.CreateRequest(null, 2002L, "100", "USDT"))
                .get("status")).isEqualTo(401);
        assertThat(c.create(new TradeApiController.CreateRequest("garbage", 2002L, "100", "USDT"))
                .get("status")).isEqualTo(401);
        // 伪造他人身份的签名（hash 与 body 不符）
        assertThat(c.create(new TradeApiController.CreateRequest(initData(USER_ID).replace("8", "9"),
                2002L, "100", "USDT")).get("status")).isEqualTo(401);

        assertThat(store.byId(1L)).as("拒绝路径绝不能落单").isEmpty();
    }

    @Test
    @DisplayName("验签通过但参数非法（金额非正/自交易/卖方缺失）→ 400 且不落单")
    void invalidParamsRejected() {
        InMemoryStore store = new InMemoryStore();
        TradeApiController c = controller(store);

        assertThat(c.create(new TradeApiController.CreateRequest(initData(USER_ID), 2002L, "-5", "USDT"))
                .get("status")).isEqualTo(400);
        assertThat(c.create(new TradeApiController.CreateRequest(initData(USER_ID), USER_ID, "100", "USDT"))
                .get("status")).isEqualTo(400);   // 自交易

        assertThat(store.byId(1L)).isEmpty();
    }

    @Test
    @DisplayName("身份取自【验签结果】而非请求体——伪造 userId 字段无效")
    void identityComesFromVerifiedInitData() {
        InMemoryStore store = new InMemoryStore();

        // 请求体里没有 userId 字段可传；即便前端伪造，也只能改 initData（改了签名就挂）
        Map<String, Object> ok = controller(store).create(
                new TradeApiController.CreateRequest(initData(USER_ID), 2002L, "100", "USDT"));

        assertThat(ok).containsEntry("ok", true);
        assertThat(store.byId(1L).orElseThrow().getBuyerUserId())
                .as("买方必须是验签得到的用户").isEqualTo(USER_ID);
    }
}
