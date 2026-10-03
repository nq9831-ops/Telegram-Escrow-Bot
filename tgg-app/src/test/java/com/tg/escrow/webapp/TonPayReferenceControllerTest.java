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
import com.tg.escrow.escrow.TonPayReferencePort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TON Pay 支付引用登记（ET-31）的行为固定测试——身份/角色/字段三层校验 +
 * 「不该登记的零登记」。
 */
class TonPayReferenceControllerTest {

    private static final long BUYER = 1001L;
    private static final long SELLER = 2002L;
    private static final long ORDER_ID = 55L;
    private static final Instant T0 = Instant.parse("2026-10-03T00:00:00Z");

    private WebAppInitDataVerifier verifier;
    private EscrowOrderLookupPort orders;
    private TonPayReferencePort references;

    private TonPayReferenceController controller() {
        verifier = mock(WebAppInitDataVerifier.class);
        orders = mock(EscrowOrderLookupPort.class);
        references = mock(TonPayReferencePort.class);
        return new TonPayReferenceController(verifier, orders, references);
    }

    private static EscrowOrder order() {
        EscrowOrder order = new EscrowOrder(BUYER, SELLER, new BigDecimal("100"), "USDT", T0);
        order.assignId(ORDER_ID);
        return order;
    }

    @Test
    @DisplayName("initData 验签失败 → 401，零登记")
    void badIdentityRejected() {
        TonPayReferenceController controller = controller();
        when(verifier.verifyUserId("bad")).thenReturn(Optional.empty());

        ResponseEntity<Map<String, Object>> resp = controller.register(
                new TonPayReferenceController.ReferenceRequest("bad", ORDER_ID, "ref-1", null));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(references, never()).register(anyLong(), any(), any());
    }

    @Test
    @DisplayName("非买方登记 → 403，零登记（TON Pay 用于买方锁资，卖方登记无语义）")
    void nonBuyerRejected() {
        TonPayReferenceController controller = controller();
        when(verifier.verifyUserId("seller")).thenReturn(Optional.of(SELLER));
        when(orders.byId(ORDER_ID)).thenReturn(Optional.of(order()));

        ResponseEntity<Map<String, Object>> resp = controller.register(
                new TonPayReferenceController.ReferenceRequest("seller", ORDER_ID, "ref-1", null));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(references, never()).register(anyLong(), any(), any());
    }

    @Test
    @DisplayName("缺 reference → 400，零登记")
    void missingReferenceRejected() {
        TonPayReferenceController controller = controller();
        when(verifier.verifyUserId("buyer")).thenReturn(Optional.of(BUYER));
        when(orders.byId(ORDER_ID)).thenReturn(Optional.of(order()));

        ResponseEntity<Map<String, Object>> resp = controller.register(
                new TonPayReferenceController.ReferenceRequest("buyer", ORDER_ID, "  ", null));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(references, never()).register(anyLong(), any(), any());
    }

    @Test
    @DisplayName("买方 + 订单存在 + 有 reference → 登记成功（bodyBase64Hash 可空）")
    void buyerRegisters() {
        TonPayReferenceController controller = controller();
        when(verifier.verifyUserId("buyer")).thenReturn(Optional.of(BUYER));
        when(orders.byId(ORDER_ID)).thenReturn(Optional.of(order()));

        ResponseEntity<Map<String, Object>> resp = controller.register(
                new TonPayReferenceController.ReferenceRequest("buyer", ORDER_ID, "ref-1", "hash-x"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsEntry("ok", true);
        verify(references).register(ORDER_ID, "ref-1", "hash-x");
    }
}
