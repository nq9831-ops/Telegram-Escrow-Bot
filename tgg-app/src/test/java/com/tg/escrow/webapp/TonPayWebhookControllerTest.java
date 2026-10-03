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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tg.escrow.escrow.EscrowOrder;
import com.tg.escrow.escrow.EscrowOrderLookupPort;
import com.tg.escrow.escrow.TonPayReferencePort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * TON Pay webhook 六步验证（ET-32）的行为固定测试——每个分支都钉住
 * 「哪个状态码 + 有没有动账」两件事（安全口径：<b>不该结算的一律零调用</b>）。
 */
class TonPayWebhookControllerTest {

    private static final String SECRET = "topsecret";
    private static final long ORDER_ID = 55L;
    private static final Instant T0 = Instant.parse("2026-10-03T00:00:00Z");

    private TonPayReferencePort references;
    private EscrowOrderLookupPort orders;

    private TonPayWebhookController controller(String secret) {
        references = mock(TonPayReferencePort.class);
        orders = mock(EscrowOrderLookupPort.class);
        Clock clock = Clock.fixed(T0, ZoneOffset.UTC);
        return new TonPayWebhookController(secret, references, orders, new ObjectMapper(), clock);
    }

    private static byte[] body(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static String sign(byte[] payload) {
        return new TonPayWebhookVerifier(SECRET).signatureOf(payload);
    }

    private static EscrowOrder order() {
        EscrowOrder order = new EscrowOrder(1001L, 2002L, new BigDecimal("100"), "USDT", T0);
        order.assignId(ORDER_ID);
        return order;
    }

    @Test
    @DisplayName("未配置密钥 → 503（未启用不是裸奔）")
    void unconfiguredIsServiceUnavailable() {
        TonPayWebhookController controller = controller("");

        ResponseEntity<Map<String, Object>> resp = controller.webhook("sig", body("{}"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        verifyNoInteractions(references, orders);
    }

    @Test
    @DisplayName("验签失败 → 401，且不碰任何端口")
    void badSignatureIsUnauthorized() {
        TonPayWebhookController controller = controller(SECRET);
        byte[] payload = body("{\"event\":\"transfer.completed\"}");

        ResponseEntity<Map<String, Object>> resp = controller.webhook("00".repeat(32), payload);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(references, orders);
    }

    @Test
    @DisplayName("非 transfer.completed 事件 → 200 忽略（webhook 惯例：勿让对端重试无关事件）")
    void otherEventsAreIgnored() {
        TonPayWebhookController controller = controller(SECRET);
        byte[] payload = body("{\"event\":\"transfer.created\"}");

        ResponseEntity<Map<String, Object>> resp = controller.webhook(sign(payload), payload);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(String.valueOf(resp.getBody().get("message"))).contains("忽略事件");
        verifyNoInteractions(references, orders);
    }

    @Test
    @DisplayName("未知 reference → 200 不处理（测试支付等正常场景），零结算")
    void unknownReferenceIsNotSettled() {
        TonPayWebhookController controller = controller(SECRET);
        when(references.isSettled("ref-x")).thenReturn(false);
        when(references.resolveOrderId("ref-x")).thenReturn(Optional.empty());
        byte[] payload = body("{\"event\":\"transfer.completed\",\"reference\":\"ref-x\"}");

        ResponseEntity<Map<String, Object>> resp = controller.webhook(sign(payload), payload);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(String.valueOf(resp.getBody().get("message"))).contains("未知 reference");
        verify(references, never()).markSettled(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("重复投递（已结算）→ 200 幂等忽略，零重复结算")
    void duplicateDeliveryIsIgnored() {
        TonPayWebhookController controller = controller(SECRET);
        when(references.isSettled("ref-1")).thenReturn(true);
        byte[] payload = body("{\"event\":\"transfer.completed\",\"reference\":\"ref-1\"}");

        ResponseEntity<Map<String, Object>> resp = controller.webhook(sign(payload), payload);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(String.valueOf(resp.getBody().get("message"))).contains("重复投递");
        verify(references, never()).markSettled(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("金额不符 → 200 但不结算（绝不按「差不多」入账）")
    void amountMismatchIsNotSettled() {
        TonPayWebhookController controller = controller(SECRET);
        when(references.isSettled("ref-1")).thenReturn(false);
        when(references.resolveOrderId("ref-1")).thenReturn(Optional.of(ORDER_ID));
        when(orders.byId(ORDER_ID)).thenReturn(Optional.of(order()));
        byte[] payload = body("{\"event\":\"transfer.completed\",\"reference\":\"ref-1\","
                + "\"amount\":\"99\",\"asset\":\"USDT\",\"txHash\":\"tx-9\"}");

        ResponseEntity<Map<String, Object>> resp = controller.webhook(sign(payload), payload);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(String.valueOf(resp.getBody().get("message"))).contains("不符");
        verify(references, never()).markSettled(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("全部吻合 → 200 且结算（金额/币种/txHash 定格）")
    void happyPathSettles() {
        TonPayWebhookController controller = controller(SECRET);
        when(references.isSettled("ref-1")).thenReturn(false);
        when(references.resolveOrderId("ref-1")).thenReturn(Optional.of(ORDER_ID));
        when(orders.byId(ORDER_ID)).thenReturn(Optional.of(order()));
        byte[] payload = body("{\"event\":\"transfer.completed\",\"reference\":\"ref-1\","
                + "\"amount\":\"100\",\"asset\":\"USDT\",\"txHash\":\"tx-9\"}");

        ResponseEntity<Map<String, Object>> resp = controller.webhook(sign(payload), payload);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(String.valueOf(resp.getBody().get("message"))).contains("已结算");
        verify(references).markSettled(eq("ref-1"), eq("tx-9"),
                eq(new BigDecimal("100")), eq("USDT"), eq(T0));
    }
}
