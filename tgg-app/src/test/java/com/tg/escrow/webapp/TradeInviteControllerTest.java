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

import com.tg.escrow.InviteLink;
import com.tg.escrow.escrow.AmountTierPolicy;
import com.tg.escrow.escrow.EscrowOrder;
import com.tg.escrow.escrow.EscrowOrderStore;
import com.tg.escrow.escrow.TradeAdmissionContext;
import com.tg.escrow.escrow.TradeAdmissionGate;
import com.tg.escrow.escrow.TradeAdmissionPolicy;
import com.tg.escrow.escrow.TradeHistoryPort;
import com.tg.escrow.escrow.TradeInvite;
import com.tg.escrow.escrow.TradeInviteService;
import com.tg.escrow.escrow.TradeInviteStore;
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
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 深链邀请 API（{@code /api/trade/invite} 与 {@code /api/trade/accept}）的行为固定测试。
 *
 * <h2>它守的是什么</h2>
 * <p>接单是<b>无命令通道</b>的入口，身份与令牌只能来自 initData 的一段签名串。本类把
 * 拒绝路径逐个钉死（验签失败 / 缺令牌 / 未知令牌 / 过期 / 自邀自接 / 已被接受），
 * 并断言「拒绝时绝不落单」。其中 {@code tamperedStartParamRejected} 是<b>反证用例</b>：
 * 若实现改从 {@code initDataUnsafe}（未签名）取令牌，它会放行——正是要证伪的形态。
 */
class TradeInviteControllerTest {

    private static final String BOT_TOKEN = "123456:TEST-TOKEN-abc";
    private static final String BOT_USERNAME = "nq9831ops_bot";
    private static final Instant T0 = Instant.parse("2026-09-24T12:00:00Z");
    private static final long BUYER_ID = 8724975623L;
    private static final long ACCEPTOR_ID = 8724000001L;
    private static final Duration TTL = Duration.ofHours(24);

    // ── 内存替身 ────────────────────────────────────────────────────────

    static final class InMemoryInviteStore implements TradeInviteStore {
        final Map<String, TradeInvite> byToken = new LinkedHashMap<>();
        long nextId = 1;

        @Override
        public TradeInvite save(TradeInvite invite) {
            if (invite.getId() == null) {
                invite.assignId(nextId++);
            }
            byToken.put(invite.getToken(), invite);
            return invite;
        }

        @Override
        public Optional<TradeInvite> byToken(String token) {
            return Optional.ofNullable(byToken.get(token));
        }
    }

    static final class InMemoryOrderStore implements EscrowOrderStore {
        final Map<Long, EscrowOrder> byId = new LinkedHashMap<>();
        long seq = 100;

        @Override
        public EscrowOrder save(EscrowOrder order) {
            order.assignId(++seq);
            byId.put(order.getId(), order);
            return order;
        }
    }

    // ── initData 造数（按官方算法自算 hash） ────────────────────────────

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

    private static String initDataWithStartParam(long userId, String startParam) {
        return initDataWithStartParam(userId, startParam, T0);
    }

    private static String initDataWithStartParam(long userId, String startParam, Instant authDate) {
        List<String> fields = new ArrayList<>(List.of(
                "auth_date=" + authDate.getEpochSecond(),
                "start_param=" + startParam,
                "user=" + URLEncoder.encode("{\"id\":" + userId + "}", StandardCharsets.UTF_8)));
        fields.sort(String::compareTo);
        return String.join("&", fields) + "&hash=" + sign(String.join("\n", fields));
    }

    // ── 装配 ────────────────────────────────────────────────────────────

    /** 记录主动通知：{@code chatId|text}。 */
    private static final class RecordingNotifications implements BotReplyPort {
        final java.util.List<String> sent = new java.util.ArrayList<>();

        @Override
        public void sendText(long chatId, String text) {
            sent.add(chatId + "|" + text);
        }

        @Override
        public void sendText(long chatId, String text, com.tg.escrow.core.NoticePolicy policy) {
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
    @DisplayName("接单成功后主动通知发起方（买方），且响应回 notified=true")
    void acceptNotifiesInviter() {
        InMemoryInviteStore invites = new InMemoryInviteStore();
        InMemoryOrderStore orders = new InMemoryOrderStore();
        RecordingNotifications notifications = new RecordingNotifications();
        TradeInviteController c = controller(invites, orders, cleanHistory(),
                Clock.fixed(T0, ZoneOffset.UTC), notifications);
        String token = (String) c.invite(
                new TradeInviteController.InviteRequest(initData(BUYER_ID), "100", "USDT"))
                .get("inviteToken");

        Map<String, Object> body = c.accept(
                new TradeInviteController.AcceptRequest(initDataWithStartParam(ACCEPTOR_ID, token)));

        assertThat(body).containsEntry("ok", true).containsEntry("notified", true);
        assertThat(notifications.sent)
                .as("发起方（买方）应收到通知")
                .anySatisfy(s -> assertThat(s).startsWith(BUYER_ID + "|"));
    }

    private static TradeInviteController controller(InMemoryInviteStore invites, InMemoryOrderStore orders,
                                                    TradeHistoryPort history, Clock clock) {
        return controller(invites, orders, history, clock, new RecordingNotifications());
    }

    private static TradeInviteController controller(InMemoryInviteStore invites, InMemoryOrderStore orders,
                                                    TradeHistoryPort history, Clock clock,
                                                    RecordingNotifications notifications) {
        TradeAdmissionGate gate = new TradeAdmissionGate(new TradeAdmissionPolicy(5, Duration.ZERO));
        AtomicInteger seq = new AtomicInteger();
        TradeInviteService svc = new TradeInviteService(gate, history, invites, orders, TTL,
                () -> "token" + seq.incrementAndGet(), clock);
        return new TradeInviteController(
                new WebAppInitDataVerifier(BOT_TOKEN, Duration.ofHours(1), clock),
                svc, new AmountTierPolicy(new BigDecimal("100"), new BigDecimal("1000")),
                new InviteLink(BOT_USERNAME),
                new TradeNotifier(notifications, clock, null));
    }

    private static TradeHistoryPort cleanHistory() {
        return id -> new TradeAdmissionContext(id, 0, null, false);
    }

    private static TradeInviteController controller(InMemoryInviteStore invites, InMemoryOrderStore orders) {
        return controller(invites, orders, cleanHistory(), Clock.fixed(T0, ZoneOffset.UTC));
    }

    // ── /invite ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("建邀请成功 → 返回含 startapp 令牌的深链与有效期")
    void inviteHappyPathReturnsDeepLink() {
        InMemoryInviteStore invites = new InMemoryInviteStore();

        Map<String, Object> body = controller(invites, new InMemoryOrderStore())
                .invite(new TradeInviteController.InviteRequest(initData(BUYER_ID), "100", "USDT"));

        assertThat(body).containsEntry("ok", true);
        String token = (String) body.get("inviteToken");
        assertThat(token).isNotBlank();
        assertThat((String) body.get("inviteUrl"))
                .isEqualTo("https://t.me/" + BOT_USERNAME + "?startapp=" + token);
        assertThat((String) body.get("expiresAt")).isEqualTo(T0.plus(TTL).toString());
        assertThat(body).containsEntry("currency", "USDT");
        assertThat(invites.byToken(token)).as("邀请应已落库").isPresent();
    }

    @Test
    @DisplayName("建邀请：验签失败 → 401 且不建邀请")
    void inviteRejectsBadInitData() {
        InMemoryInviteStore invites = new InMemoryInviteStore();
        TradeInviteController c = controller(invites, new InMemoryOrderStore());

        assertThat(c.invite(new TradeInviteController.InviteRequest(null, "100", "USDT")).get("status"))
                .isEqualTo(401);
        assertThat(c.invite(new TradeInviteController.InviteRequest("garbage", "100", "USDT")).get("status"))
                .isEqualTo(401);

        assertThat(invites.byToken).isEmpty();
    }

    @Test
    @DisplayName("建邀请：白名单外币种 → 400 且不建邀请")
    void inviteRejectsUnsupportedCurrency() {
        InMemoryInviteStore invites = new InMemoryInviteStore();

        Map<String, Object> body = controller(invites, new InMemoryOrderStore())
                .invite(new TradeInviteController.InviteRequest(initData(BUYER_ID), "100", "BTC"));

        assertThat(body.get("status")).isEqualTo(400);
        assertThat(invites.byToken).isEmpty();
    }

    @Test
    @DisplayName("建邀请：发起方已满并发 → 409 且不建邀请")
    void inviteRejectsWhenGateBlocks() {
        InMemoryInviteStore invites = new InMemoryInviteStore();
        TradeHistoryPort busy = id -> new TradeAdmissionContext(id, 5, null, false);

        Map<String, Object> body = controller(invites, new InMemoryOrderStore(), busy,
                Clock.fixed(T0, ZoneOffset.UTC))
                .invite(new TradeInviteController.InviteRequest(initData(BUYER_ID), "100", "USDT"));

        assertThat(body.get("status")).isEqualTo(409);
        assertThat(invites.byToken).isEmpty();
    }

    // ── /accept ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("接单成功 → 物化双方齐全的 CONFIRMED 订单，邀请被消费")
    void acceptHappyPathMaterializesOrder() {
        InMemoryInviteStore invites = new InMemoryInviteStore();
        InMemoryOrderStore orders = new InMemoryOrderStore();
        TradeInviteController c = controller(invites, orders);
        String token = (String) c.invite(
                new TradeInviteController.InviteRequest(initData(BUYER_ID), "100", "USDT")).get("inviteToken");

        Map<String, Object> body = c.accept(
                new TradeInviteController.AcceptRequest(initDataWithStartParam(ACCEPTOR_ID, token)));

        assertThat(body).containsEntry("ok", true);
        long orderId = ((Number) body.get("orderId")).longValue();
        EscrowOrder order = orders.byId.get(orderId);
        assertThat(order).isNotNull();
        assertThat(order.currentState()).isEqualTo(EscrowOrder.State.CONFIRMED);
        assertThat(order.getBuyerUserId()).isEqualTo(BUYER_ID);
        assertThat(order.getSellerUserId()).isEqualTo(ACCEPTOR_ID);
        assertThat(invites.byToken(token).orElseThrow().isConsumed()).isTrue();
    }

    @Test
    @DisplayName("接单：验签失败 → 401 且不落单")
    void acceptRejectsBadInitData() {
        InMemoryOrderStore orders = new InMemoryOrderStore();
        TradeInviteController c = controller(new InMemoryInviteStore(), orders);

        assertThat(c.accept(new TradeInviteController.AcceptRequest("garbage")).get("status")).isEqualTo(401);
        assertThat(orders.byId).isEmpty();
    }

    @Test
    @DisplayName("接单：initData 无 start_param（普通入口）→ 400 且不落单")
    void acceptWithoutStartParamFails() {
        InMemoryOrderStore orders = new InMemoryOrderStore();
        TradeInviteController c = controller(new InMemoryInviteStore(), orders);

        assertThat(c.accept(new TradeInviteController.AcceptRequest(initData(ACCEPTOR_ID))).get("status"))
                .isEqualTo(400);
        assertThat(orders.byId).isEmpty();
    }

    @Test
    @DisplayName("【反证】篡改 start_param（不改 hash）→ 401——令牌必须来自签名串")
    void tamperedStartParamRejected() {
        InMemoryInviteStore invites = new InMemoryInviteStore();
        InMemoryOrderStore orders = new InMemoryOrderStore();
        TradeInviteController c = controller(invites, orders);
        String token = (String) c.invite(
                new TradeInviteController.InviteRequest(initData(BUYER_ID), "100", "USDT")).get("inviteToken");

        String tampered = initDataWithStartParam(ACCEPTOR_ID, token).replace(token, "forged-token-xyz");
        Map<String, Object> body = c.accept(new TradeInviteController.AcceptRequest(tampered));

        assertThat(body.get("status"))
                .as("若令牌取自未签名的 initDataUnsafe，此篡改会被放行——本用例正是要证伪它")
                .isEqualTo(401);
        assertThat(orders.byId).isEmpty();
    }

    @Test
    @DisplayName("接单：未知令牌 → 404 且不落单")
    void acceptUnknownTokenFails() {
        InMemoryOrderStore orders = new InMemoryOrderStore();
        TradeInviteController c = controller(new InMemoryInviteStore(), orders);

        assertThat(c.accept(new TradeInviteController.AcceptRequest(
                initDataWithStartParam(ACCEPTOR_ID, "no-such-token"))).get("status")).isEqualTo(404);
        assertThat(orders.byId).isEmpty();
    }

    @Test
    @DisplayName("接单：过期邀请 → 410 且不落单")
    void acceptExpiredInviteFails() {
        InMemoryInviteStore invites = new InMemoryInviteStore();
        InMemoryOrderStore orders = new InMemoryOrderStore();
        String token = (String) controller(invites, orders).invite(
                new TradeInviteController.InviteRequest(initData(BUYER_ID), "100", "USDT")).get("inviteToken");

        // 用「过期之后」的时钟再建一个控制器，共享同一存储；
        // auth_date 取「有效期之后但仍在验签时效（1h）内」——使验签先通过，真正走到过期守卫
        Clock later = Clock.fixed(T0.plus(TTL).plus(Duration.ofHours(1)), ZoneOffset.UTC);
        TradeInviteController expired = controller(invites, orders, cleanHistory(), later);
        Instant acceptAt = T0.plus(TTL).plus(Duration.ofMinutes(30));

        assertThat(expired.accept(new TradeInviteController.AcceptRequest(
                initDataWithStartParam(ACCEPTOR_ID, token, acceptAt))).get("status")).isEqualTo(410);
        assertThat(orders.byId).isEmpty();
    }

    @Test
    @DisplayName("接单：发起人接自己的邀请 → 400 且不落单")
    void acceptSelfInviteFails() {
        InMemoryInviteStore invites = new InMemoryInviteStore();
        InMemoryOrderStore orders = new InMemoryOrderStore();
        TradeInviteController c = controller(invites, orders);
        String token = (String) c.invite(
                new TradeInviteController.InviteRequest(initData(BUYER_ID), "100", "USDT")).get("inviteToken");

        assertThat(c.accept(new TradeInviteController.AcceptRequest(
                initDataWithStartParam(BUYER_ID, token))).get("status")).isEqualTo(400);
        assertThat(orders.byId).isEmpty();
    }

    @Test
    @DisplayName("接单：已被接受 → 409 且不落第二单")
    void acceptConsumedInviteFails() {
        InMemoryInviteStore invites = new InMemoryInviteStore();
        InMemoryOrderStore orders = new InMemoryOrderStore();
        TradeInviteController c = controller(invites, orders);
        String token = (String) c.invite(
                new TradeInviteController.InviteRequest(initData(BUYER_ID), "100", "USDT")).get("inviteToken");

        assertThat(c.accept(new TradeInviteController.AcceptRequest(
                initDataWithStartParam(ACCEPTOR_ID, token))).get("status")).isNull();  // ok（无 status 字段）
        Map<String, Object> second = c.accept(new TradeInviteController.AcceptRequest(
                initDataWithStartParam(8724000002L, token)));

        assertThat(second.get("status")).isEqualTo(409);
        assertThat(orders.byId).hasSize(1);
    }

    @Test
    @DisplayName("接单：目标时刻发起人已不可承接 → 409 且不落单")
    void acceptWhenBuyerNotAdmittedFails() {
        InMemoryInviteStore invites = new InMemoryInviteStore();
        InMemoryOrderStore orders = new InMemoryOrderStore();
        String token = (String) controller(invites, orders).invite(
                new TradeInviteController.InviteRequest(initData(BUYER_ID), "100", "USDT")).get("inviteToken");

        TradeHistoryPort busy = id -> new TradeAdmissionContext(id, 5, null, false);
        TradeInviteController gated = controller(invites, orders, busy, Clock.fixed(T0, ZoneOffset.UTC));

        assertThat(gated.accept(new TradeInviteController.AcceptRequest(
                initDataWithStartParam(ACCEPTOR_ID, token))).get("status")).isEqualTo(409);
        assertThat(orders.byId).isEmpty();
    }
}
