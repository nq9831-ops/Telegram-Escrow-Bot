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
import com.tg.escrow.escrow.EscrowMultiSigRule.Party;
import com.tg.escrow.escrow.EscrowVerdict.Outcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 裁决编排（S5 Wave 2 · 方案 A）：链下多签裁决 → 订单终态迁移。
 *
 * <p>守卫链全部 fail-closed：订单须 DISPUTED、裁决单须指向本单、签名须过
 * {@link EscrowVerdictGuard}（含 {@link EscrowMultiSigRule}：≥2 方且必含联邦）。
 * 签名集不入库存档（`escrow_orders` 无承载列、不新增表）——留痕在编排层日志与回执。
 */
class EscrowVerdictServiceTest {

    private static final long ORDER_ID = 42L;
    private static final Instant NOW = Instant.parse("2026-09-29T12:00:00Z");
    private static final BigDecimal AMOUNT = new BigDecimal("100");

    /** 按预置答案放行/拒绝的验签桩（答案里没有的方视为不通过——fail-closed 同向）。 */
    private static final class StubVerifier implements PartySignatureVerifier {
        private final Map<Party, Boolean> answers = new EnumMap<>(Party.class);

        StubVerifier pass(Party party) {
            answers.put(party, true);
            return this;
        }

        @Override
        public boolean verify(Party party, byte[] payload, byte[] signature) {
            return answers.getOrDefault(party, false);
        }
    }

    private static final class InMemoryStore implements EscrowOrderStore {
        EscrowOrder last;

        @Override
        public EscrowOrder save(EscrowOrder order) {
            this.last = order;
            return order;
        }
    }

    private static EscrowOrder disputedOrder() {
        EscrowOrder order = new EscrowOrder(1001L, 2002L, AMOUNT, "USDT", NOW);
        order.assignId(ORDER_ID);
        order.markLocked(NOW);
        order.markDisputed("货不对板", NOW);
        return order;
    }

    private static EscrowVerdict verdict(Outcome outcome, long orderId) {
        return new EscrowVerdict(orderId, outcome, "联邦裁决：支持买方", NOW);
    }

    private static EscrowVerdictService service(InMemoryStore store, PartySignatureVerifier verifier) {
        return new EscrowVerdictService(store, Clock.fixed(NOW, ZoneOffset.UTC), verifier);
    }

    @Test
    @DisplayName("RELEASE 裁决：DISPUTED → RELEASED（联邦+买方 2/3 且含联邦）")
    void releaseVerdictMovesDisputedOrderToReleased() {
        InMemoryStore store = new InMemoryStore();
        EscrowVerdictService svc = service(store, new StubVerifier().pass(Party.FEDERATION).pass(Party.BUYER));
        EscrowOrder order = disputedOrder();

        svc.execute(order, verdict(Outcome.RELEASE, ORDER_ID),
                Map.of(Party.FEDERATION, new byte[]{1}, Party.BUYER, new byte[]{2}));

        assertThat(order.currentState()).isEqualTo(EscrowOrder.State.RELEASED);
        assertThat(store.last).isSameAs(order);
    }

    @Test
    @DisplayName("REFUND 裁决：DISPUTED → REFUNDED，裁决理由落 reason 列")
    void refundVerdictMovesToRefundedWithReason() {
        InMemoryStore store = new InMemoryStore();
        EscrowVerdictService svc = service(store, new StubVerifier().pass(Party.FEDERATION).pass(Party.SELLER));
        EscrowOrder order = disputedOrder();

        svc.execute(order, verdict(Outcome.REFUND, ORDER_ID),
                Map.of(Party.FEDERATION, new byte[]{1}, Party.SELLER, new byte[]{2}));

        assertThat(order.currentState()).isEqualTo(EscrowOrder.State.REFUNDED);
        assertThat(order.getReason()).isEqualTo("联邦裁决：支持买方");
    }

    @Test
    @DisplayName("拒绝：订单不在 DISPUTED 态")
    void verdictRejectedWhenOrderNotDisputed() {
        EscrowOrder order = new EscrowOrder(1001L, 2002L, AMOUNT, "USDT", NOW);
        order.assignId(ORDER_ID);
        EscrowVerdictService svc = service(new InMemoryStore(), new StubVerifier().pass(Party.FEDERATION));

        assertThatThrownBy(() -> svc.execute(order, verdict(Outcome.RELEASE, ORDER_ID),
                Map.of(Party.FEDERATION, new byte[]{1})))
                .isInstanceOf(EscrowException.class)
                .hasMessageContaining("仅争议中的订单可裁决");
        assertThat(order.currentState()).isNotEqualTo(EscrowOrder.State.RELEASED);
    }

    @Test
    @DisplayName("拒绝：裁决单指向别的订单")
    void verdictRejectedWhenOrderIdMismatched() {
        EscrowOrder order = disputedOrder();
        EscrowVerdictService svc = service(new InMemoryStore(), new StubVerifier().pass(Party.FEDERATION));

        assertThatThrownBy(() -> svc.execute(order, verdict(Outcome.RELEASE, ORDER_ID + 1),
                Map.of(Party.FEDERATION, new byte[]{1})))
                .isInstanceOf(EscrowException.class)
                .hasMessageContaining("不匹配");
    }

    @Test
    @DisplayName("拒绝：签名不足（仅联邦一方，不足 2/3）")
    void verdictRejectedWhenSignaturesInsufficient() {
        EscrowOrder order = disputedOrder();
        EscrowVerdictService svc = service(new InMemoryStore(), new StubVerifier().pass(Party.FEDERATION));

        assertThatThrownBy(() -> svc.execute(order, verdict(Outcome.RELEASE, ORDER_ID),
                Map.of(Party.FEDERATION, new byte[]{1})))
                .isInstanceOf(EscrowException.class)
                .hasMessageContaining("多签");
        assertThat(order.currentState()).isEqualTo(EscrowOrder.State.DISPUTED);
    }

    @Test
    @DisplayName("拒绝：买卖双方组合（2 方但无联邦——C1 明令无效）")
    void verdictRejectedWhenBuyerSellerOnly() {
        EscrowOrder order = disputedOrder();
        EscrowVerdictService svc = service(new InMemoryStore(),
                new StubVerifier().pass(Party.BUYER).pass(Party.SELLER));

        assertThatThrownBy(() -> svc.execute(order, verdict(Outcome.RELEASE, ORDER_ID),
                Map.of(Party.BUYER, new byte[]{1}, Party.SELLER, new byte[]{2})))
                .isInstanceOf(EscrowException.class)
                .hasMessageContaining("多签");
    }

    @Test
    @DisplayName("拒绝：伪造签名被剔除后不足（验签 fail-closed 同向）")
    void verdictRejectedWhenForgedSignatureDropped() {
        EscrowOrder order = disputedOrder();
        // 只放行联邦——买家的签名是伪造的（verify=false），剔除后仅 1 方
        EscrowVerdictService svc = service(new InMemoryStore(), new StubVerifier().pass(Party.FEDERATION));

        assertThatThrownBy(() -> svc.execute(order, verdict(Outcome.RELEASE, ORDER_ID),
                Map.of(Party.FEDERATION, new byte[]{1}, Party.BUYER, new byte[]{2})))
                .isInstanceOf(EscrowException.class)
                .hasMessageContaining("多签");
    }

    @Test
    @DisplayName("拒绝：裁决单缺失（null 不当「无裁决」处理）")
    void nullVerdictRejected() {
        EscrowOrder order = disputedOrder();
        EscrowVerdictService svc = service(new InMemoryStore(), new StubVerifier().pass(Party.FEDERATION));

        assertThatThrownBy(() -> svc.execute(order, null, Map.of(Party.FEDERATION, new byte[]{1})))
                .isInstanceOf(EscrowException.class);
    }

    @Test
    @DisplayName("拒绝：签发时刻过旧（超出有效窗口——重放/陈旧签名防线）")
    void staleIssuedAtRejected() {
        EscrowOrder order = disputedOrder();
        EscrowVerdictService svc = service(new InMemoryStore(),
                new StubVerifier().pass(Party.FEDERATION).pass(Party.BUYER));
        EscrowVerdict stale = new EscrowVerdict(ORDER_ID, Outcome.RELEASE, "联邦裁决：支持买方",
                NOW.minus(Duration.ofMinutes(30)));

        assertThatThrownBy(() -> svc.execute(order, stale,
                Map.of(Party.FEDERATION, new byte[]{1}, Party.BUYER, new byte[]{2})))
                .isInstanceOf(EscrowException.class)
                .hasMessageContaining("签发时刻");
    }

    @Test
    @DisplayName("拒绝：签发时刻在将来（时钟偏移防线）")
    void futureIssuedAtRejected() {
        EscrowOrder order = disputedOrder();
        EscrowVerdictService svc = service(new InMemoryStore(),
                new StubVerifier().pass(Party.FEDERATION).pass(Party.BUYER));
        EscrowVerdict future = new EscrowVerdict(ORDER_ID, Outcome.RELEASE, "联邦裁决：支持买方",
                NOW.plus(Duration.ofMinutes(30)));

        assertThatThrownBy(() -> svc.execute(order, future,
                Map.of(Party.FEDERATION, new byte[]{1}, Party.BUYER, new byte[]{2})))
                .isInstanceOf(EscrowException.class)
                .hasMessageContaining("签发时刻");
    }

    @Test
    @DisplayName("窗口内签发时刻正常执行（窗口边界不误伤）")
    void issuedAtWithinWindowAccepted() {
        InMemoryStore store = new InMemoryStore();
        EscrowVerdictService svc = service(store,
                new StubVerifier().pass(Party.FEDERATION).pass(Party.BUYER));
        EscrowOrder order = disputedOrder();
        EscrowVerdict verdict = new EscrowVerdict(ORDER_ID, Outcome.RELEASE, "联邦裁决：支持买方",
                NOW.minus(Duration.ofMinutes(1)));

        svc.execute(order, verdict, Map.of(Party.FEDERATION, new byte[]{1}, Party.BUYER, new byte[]{2}));

        assertThat(order.currentState()).isEqualTo(EscrowOrder.State.RELEASED);
    }
}
