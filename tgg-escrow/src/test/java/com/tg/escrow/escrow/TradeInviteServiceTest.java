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
package com.tg.escrow.escrow;

import com.tg.escrow.common.EscrowException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 邀请创建 / 接单服务的行为固定测试（纯本地，用内存替身）。
 */
class TradeInviteServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-24T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static final long BUYER = 1001L;
    private static final long ACCEPTOR = 2002L;
    private static final BigDecimal AMOUNT = new BigDecimal("100.00");
    private static final Duration TTL = Duration.ofHours(24);
    private static final String TOKEN = "deadbeefdeadbeefdeadbeefdeadbeef";

    private static final TradeAdmissionPolicy POLICY = new TradeAdmissionPolicy(1, Duration.ofHours(24));

    private static TradeAdmissionContext cleanHistory(long subject) {
        return new TradeAdmissionContext(subject, 0, null, false);
    }

    private static TradeAdmissionContext history(long subject, int active, boolean disputed) {
        return new TradeAdmissionContext(subject, active, null, disputed);
    }

    /** 内存邀请存储：可注入失败以模拟持久化异常。 */
    static final class InMemoryInviteStore implements TradeInviteStore {
        final Map<String, TradeInvite> byToken = new LinkedHashMap<>();
        final AtomicInteger saves = new AtomicInteger();
        long nextId = 1;
        RuntimeException failOnSave;

        @Override
        public TradeInvite save(TradeInvite invite) {
            if (failOnSave != null) {
                throw failOnSave;
            }
            saves.incrementAndGet();
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

    static final class RecordingOrderStore implements EscrowOrderStore {
        final AtomicInteger saves = new AtomicInteger();
        EscrowOrder last;
        long nextId = 100;

        @Override
        public EscrowOrder save(EscrowOrder order) {
            saves.incrementAndGet();
            order.assignId(nextId++);
            this.last = order;
            return order;
        }
    }

    private static TradeInviteService service(TradeInviteStore invites, EscrowOrderStore orders,
                                              TradeHistoryPort history) {
        Supplier<String> tokenGen = () -> TOKEN;
        return new TradeInviteService(new TradeAdmissionGate(POLICY), history, invites, orders,
                TTL, tokenGen, CLOCK);
    }

    private static TradeInviteService service(TradeInviteStore invites, EscrowOrderStore orders) {
        return service(invites, orders, subject -> cleanHistory(subject));
    }

    // ── create ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("发起人无历史 → 创建邀请成功，令牌与到期时刻（now+ttl）落地")
    void createStoresInviteWithTokenAndExpiry() {
        InMemoryInviteStore invites = new InMemoryInviteStore();
        RecordingOrderStore orders = new RecordingOrderStore();

        TradeInviteCreationResult result = service(invites, orders).create(BUYER, AMOUNT, "USDT");

        assertThat(result.status()).isEqualTo(TradeInviteCreationResult.Status.CREATED);
        assertThat(result.invite().getToken()).isEqualTo(TOKEN);
        assertThat(result.invite().getBuyerUserId()).isEqualTo(BUYER);
        assertThat(result.invite().getExpiresAt()).isEqualTo(NOW.plus(TTL));
        assertThat(result.invite().isConsumed()).isFalse();
        assertThat(invites.saves.get()).isEqualTo(1);
        assertThat(orders.saves.get())
                .as("创建邀请阶段绝不落单——订单只会在对方接单时物化")
                .isZero();
    }

    @Test
    @DisplayName("发起人已达并发上限 → 拒绝，且绝不存邀请")
    void createRejectedByAdmissionGate() {
        InMemoryInviteStore invites = new InMemoryInviteStore();
        RecordingOrderStore orders = new RecordingOrderStore();

        TradeInviteCreationResult result = service(invites, orders,
                subject -> history(subject, 1, false)).create(BUYER, AMOUNT, "USDT");

        assertThat(result.status()).isEqualTo(TradeInviteCreationResult.Status.REJECTED);
        assertThat(result.decision().reason()).isEqualTo(TradeAdmissionDecision.Reason.CONCURRENT_LIMIT);
        assertThat(result.invite()).isNull();
        assertThat(invites.saves.get()).isZero();
    }

    @Test
    @DisplayName("币种不在白名单 → 装配邀请时即抛，绝不存邀请")
    void createRejectsUnsupportedCurrency() {
        InMemoryInviteStore invites = new InMemoryInviteStore();

        assertThatThrownBy(() -> service(invites, new RecordingOrderStore()).create(BUYER, AMOUNT, "BTC"))
                .isInstanceOf(EscrowException.class)
                .hasMessageContaining("不支持");
        assertThat(invites.saves.get()).isZero();
    }

    // ── accept ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("接单成功 → 物化双方齐全的 CONFIRMED 订单，邀请被标记消费并记录订单号")
    void acceptMaterializesConfirmedOrderAndConsumesInvite() {
        InMemoryInviteStore invites = new InMemoryInviteStore();
        RecordingOrderStore orders = new RecordingOrderStore();
        TradeInviteService svc = service(invites, orders);
        svc.create(BUYER, AMOUNT, "USDT");

        EscrowOrder order = svc.accept(TOKEN, ACCEPTOR);

        assertThat(order.currentState()).isEqualTo(EscrowOrder.State.CONFIRMED);
        assertThat(order.getBuyerUserId()).isEqualTo(BUYER);
        assertThat(order.getSellerUserId()).isEqualTo(ACCEPTOR);
        assertThat(order.getAmount()).isEqualByComparingTo(AMOUNT);
        assertThat(order.getCurrency()).isEqualTo("USDT");
        assertThat(orders.saves.get()).isEqualTo(1);

        TradeInvite stored = invites.byToken(TOKEN).orElseThrow();
        assertThat(stored.isConsumed()).isTrue();
        assertThat(stored.getAcceptedOrderId()).isEqualTo(order.getId());
    }

    @Test
    @DisplayName("令牌不存在 → NOT_FOUND")
    void acceptUnknownTokenFails() {
        assertThatThrownBy(() -> service(new InMemoryInviteStore(), new RecordingOrderStore())
                .accept("no-such-token", ACCEPTOR))
                .isInstanceOf(TradeInviteException.class)
                .extracting(e -> ((TradeInviteException) e).reason())
                .isEqualTo(TradeInviteException.Reason.NOT_FOUND);
    }

    @Test
    @DisplayName("已过期的邀请 → EXPIRED，且绝不落单")
    void acceptExpiredInviteFails() {
        InMemoryInviteStore invites = new InMemoryInviteStore();
        RecordingOrderStore orders = new RecordingOrderStore();
        // 手工塞一个已过期的邀请（构造于 TTL 之外）
        invites.save(new TradeInvite(BUYER, AMOUNT, "USDT", TOKEN,
                NOW.minus(Duration.ofDays(2)), NOW.minus(Duration.ofHours(1))));

        assertThatThrownBy(() -> service(invites, orders).accept(TOKEN, ACCEPTOR))
                .isInstanceOf(TradeInviteException.class)
                .extracting(e -> ((TradeInviteException) e).reason())
                .isEqualTo(TradeInviteException.Reason.EXPIRED);
        assertThat(orders.saves.get()).isZero();
    }

    @Test
    @DisplayName("已被接受的邀请 → ALREADY_ACCEPTED，且绝不落第二单")
    void acceptConsumedInviteFails() {
        InMemoryInviteStore invites = new InMemoryInviteStore();
        RecordingOrderStore orders = new RecordingOrderStore();
        TradeInviteService svc = service(invites, orders);
        svc.create(BUYER, AMOUNT, "USDT");
        svc.accept(TOKEN, ACCEPTOR);

        assertThatThrownBy(() -> svc.accept(TOKEN, 3003L))
                .isInstanceOf(TradeInviteException.class)
                .extracting(e -> ((TradeInviteException) e).reason())
                .isEqualTo(TradeInviteException.Reason.ALREADY_ACCEPTED);
        assertThat(orders.saves.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("发起人接受自己的邀请 → SELF_ACCEPT，且绝不落单")
    void acceptSelfInviteFails() {
        InMemoryInviteStore invites = new InMemoryInviteStore();
        RecordingOrderStore orders = new RecordingOrderStore();
        TradeInviteService svc = service(invites, orders);
        svc.create(BUYER, AMOUNT, "USDT");

        assertThatThrownBy(() -> svc.accept(TOKEN, BUYER))
                .isInstanceOf(TradeInviteException.class)
                .extracting(e -> ((TradeInviteException) e).reason())
                .isEqualTo(TradeInviteException.Reason.SELF_ACCEPT);
        assertThat(orders.saves.get()).isZero();
    }

    @Test
    @DisplayName("接单时发起人已不可承接（并发/争议）→ NOT_ADMITTED，绝不落单")
    void acceptWhenBuyerNotAdmittedFails() {
        InMemoryInviteStore invites = new InMemoryInviteStore();
        RecordingOrderStore orders = new RecordingOrderStore();
        // 先用干净历史建邀请，再由「更新过的」历史使接单被门禁拒
        service(invites, orders).create(BUYER, AMOUNT, "USDT");

        TradeInviteService gated = service(invites, orders, subject -> history(subject, 1, false));
        assertThatThrownBy(() -> gated.accept(TOKEN, ACCEPTOR))
                .isInstanceOf(TradeInviteException.class)
                .extracting(e -> ((TradeInviteException) e).reason())
                .isEqualTo(TradeInviteException.Reason.NOT_ADMITTED);
        assertThat(orders.saves.get()).isZero();
    }

    @Test
    @DisplayName("并发抢接：邀请消费写入触发乐观锁冲突 → 原样上抛 ConcurrentOrderUpdateException")
    void concurrentAcceptSurfaceConflict() {
        InMemoryInviteStore invites = new InMemoryInviteStore();
        RecordingOrderStore orders = new RecordingOrderStore();
        TradeInviteService svc = service(invites, orders);
        svc.create(BUYER, AMOUNT, "USDT");
        // 消费（save）阶段模拟他人已抢先提交
        invites.failOnSave = new ConcurrentOrderUpdateException("已被他人变更");

        // 订单与邀请消费的「同生共死」由服务方法的 @Transactional 保证：
        // 本测试用内存替身（无事务、无回滚），只固定「冲突必须原样上抛」这一契约；
        // 真实的回滚/乐观锁行为由持久化层测试在真库上验证。
        assertThatThrownBy(() -> svc.accept(TOKEN, ACCEPTOR))
                .isInstanceOf(ConcurrentOrderUpdateException.class);
    }

    @Test
    @DisplayName("未提供依赖 → 构造即抛")
    void missingDependenciesFailFast() {
        InMemoryInviteStore invites = new InMemoryInviteStore();
        RecordingOrderStore orders = new RecordingOrderStore();
        Supplier<String> gen = () -> TOKEN;
        assertThatThrownBy(() -> new TradeInviteService(null, s -> cleanHistory(s), invites, orders, TTL, gen, CLOCK))
                .isInstanceOf(EscrowException.class);
        assertThatThrownBy(() -> new TradeInviteService(new TradeAdmissionGate(POLICY), null, invites, orders, TTL, gen, CLOCK))
                .isInstanceOf(EscrowException.class);
        assertThatThrownBy(() -> new TradeInviteService(new TradeAdmissionGate(POLICY), s -> cleanHistory(s), null, orders, TTL, gen, CLOCK))
                .isInstanceOf(EscrowException.class);
        assertThatThrownBy(() -> new TradeInviteService(new TradeAdmissionGate(POLICY), s -> cleanHistory(s), invites, null, TTL, gen, CLOCK))
                .isInstanceOf(EscrowException.class);
        assertThatThrownBy(() -> new TradeInviteService(new TradeAdmissionGate(POLICY), s -> cleanHistory(s), invites, orders, null, gen, CLOCK))
                .isInstanceOf(EscrowException.class);
        assertThatThrownBy(() -> new TradeInviteService(new TradeAdmissionGate(POLICY), s -> cleanHistory(s), invites, orders, TTL, null, CLOCK))
                .isInstanceOf(EscrowException.class);
        assertThatThrownBy(() -> new TradeInviteService(new TradeAdmissionGate(POLICY), s -> cleanHistory(s), invites, orders, TTL, gen, null))
                .isInstanceOf(EscrowException.class);
    }
}
