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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 对账端点（ET-19 步 2 触发面）的行为固定测试——请求级错误 400；业务结论（含 DANGER）
 * 一律 200 且结论在 body。
 */
class AdminReconcileControllerTest {

    @Test
    @DisplayName("缺 orderId → 400")
    void missingOrderIdRejected() {
        AdminReconcileController controller = new AdminReconcileController(
                mock(EscrowChainReconciler.class));

        ResponseEntity<Map<String, Object>> resp =
                controller.reconcile(new AdminReconcileController.ReconcileRequest(null));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("DANGER 结论也 200——业务结论不是 HTTP 错误，结论如实出参")
    void dangerIsStillOkHttp() {
        EscrowChainReconciler reconciler = mock(EscrowChainReconciler.class);
        when(reconciler.reconcile(77L)).thenReturn(new EscrowChainReconciler.Result(77L, 1,
                "RELEASED", EscrowChainReconciler.Verdict.DANGER, List.of(),
                "链下已登记终态（RELEASED）但链上资金仍在合约（state=1）"));
        AdminReconcileController controller = new AdminReconcileController(reconciler);

        ResponseEntity<Map<String, Object>> resp =
                controller.reconcile(new AdminReconcileController.ReconcileRequest(77L));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody())
                .containsEntry("ok", true)
                .containsEntry("verdict", "DANGER")
                .containsEntry("chainState", 1)
                .containsEntry("orderState", "RELEASED");
        assertThat(String.valueOf(resp.getBody().get("note"))).contains("资金仍在合约");
    }

    @Test
    @DisplayName("构造：对账器不可为空")
    void reconcilerRequired() {
        assertThatThrownBy(() -> new AdminReconcileController(null))
                .isInstanceOf(com.tg.escrow.common.TggException.class);
    }
}
