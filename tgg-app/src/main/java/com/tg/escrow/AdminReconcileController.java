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

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Admin API（S6）：链上对账触发面（ET-19 步 2）——无调度器架构下的对账入口
 * （部署者外部 cron 定时调或用管理面板手工调）。
 *
 * <p><b>HTTP 语义</b>：请求本身成功一律 200——{@code verdict} 是业务结论
 * （{@code DANGER}/{@code CHAIN_UNAVAILABLE} 等不是 HTTP 错误，结论在 body 里如实呈现）。
 * 鉴权同其余 /admin（Basic；未配置凭据一律 401）。
 */
@RestController
@RequestMapping("/admin/reconcile")
public class AdminReconcileController {

    /** 对账请求。 */
    public record ReconcileRequest(Long orderId) {
    }

    private final EscrowChainReconciler reconciler;

    public AdminReconcileController(EscrowChainReconciler reconciler) {
        if (reconciler == null) {
            throw new com.tg.escrow.common.TggException("Admin API：对账器不可为空");
        }
        this.reconciler = reconciler;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> reconcile(
            @RequestBody(required = false) ReconcileRequest req) {
        if (req == null || req.orderId() == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("ok", false, "error", "缺少订单号（orderId）"));
        }
        EscrowChainReconciler.Result result = reconciler.reconcile(req.orderId());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("verdict", result.verdict().name());
        body.put("orderState", result.orderState());
        body.put("chainState", result.chainState());
        body.put("actions", result.actions());
        if (result.note() != null) {
            body.put("note", result.note());
        }
        return ResponseEntity.ok(body);
    }
}
