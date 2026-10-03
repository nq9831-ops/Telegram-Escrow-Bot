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

import com.tg.escrow.escrow.EscrowOrder;
import com.tg.escrow.escrow.EscrowOrderLookupPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 交易状态查询端点（ET-02 Web 形态）——身份/存在性/当事方三层闸门 + 字段出参。
 */
class TradeStatusControllerTest {

    private static final long BUYER = 1001L;
    private static final long SELLER = 2002L;
    private static final long STRANGER = 3003L;
    private static final long ORDER = 55L;
    private static final Instant T0 = Instant.parse("2026-10-03T00:00:00Z");

    private WebAppInitDataVerifier verifier;
    private EscrowOrderLookupPort orders;

    private TradeStatusController controller() {
        verifier = mock(WebAppInitDataVerifier.class);
        orders = mock(EscrowOrderLookupPort.class);
        return new TradeStatusController(verifier, orders);
    }

    private static EscrowOrder order() {
        EscrowOrder order = new EscrowOrder(BUYER, SELLER, new BigDecimal("100"), "USDT", T0);
        order.assignId(ORDER);
        order.markLocked(T0);
        return order;
    }

    @Test
    @DisplayName("initData 验签失败 → 401")
    void badIdentityRejected() {
        TradeStatusController controller = controller();
        when(verifier.verifyUserId("bad")).thenReturn(Optional.empty());

        ResponseEntity<Map<String, Object>> resp = controller.status(
                new TradeStatusController.StatusRequest("bad", ORDER));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("订单不存在 → 404")
    void missingOrderRejected() {
        TradeStatusController controller = controller();
        when(verifier.verifyUserId("buyer")).thenReturn(Optional.of(BUYER));
        when(orders.byId(ORDER)).thenReturn(Optional.empty());

        ResponseEntity<Map<String, Object>> resp = controller.status(
                new TradeStatusController.StatusRequest("buyer", ORDER));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("非当事方（既非买方也非卖方）→ 403（订单号可枚举，必须限定可见性）")
    void strangerRejected() {
        TradeStatusController controller = controller();
        when(verifier.verifyUserId("stranger")).thenReturn(Optional.of(STRANGER));
        when(orders.byId(ORDER)).thenReturn(Optional.of(order()));

        ResponseEntity<Map<String, Object>> resp = controller.status(
                new TradeStatusController.StatusRequest("stranger", ORDER));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("卖方也可查（当事方双向）→ 200 且域视图文案同源出参")
    void sellerCanQuery() {
        TradeStatusController controller = controller();
        when(verifier.verifyUserId("seller")).thenReturn(Optional.of(SELLER));
        when(orders.byId(ORDER)).thenReturn(Optional.of(order()));

        ResponseEntity<Map<String, Object>> resp = controller.status(
                new TradeStatusController.StatusRequest("seller", ORDER));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody())
                .containsEntry("ok", true)
                .containsEntry("orderId", ORDER)
                .containsEntry("state", "LOCKED")
                .containsEntry("currency", "USDT")
                .containsEntry("amount", "100");
        assertThat(String.valueOf(resp.getBody().get("summary"))).isNotBlank();
        assertThat(String.valueOf(resp.getBody().get("nextStep"))).isNotBlank();
    }

    @Test
    @DisplayName("构造：依赖不可为空")
    void dependenciesRequired() {
        assertThatThrownBy(() -> new TradeStatusController(null, null))
                .isInstanceOf(com.tg.escrow.common.TggException.class);
    }
}
