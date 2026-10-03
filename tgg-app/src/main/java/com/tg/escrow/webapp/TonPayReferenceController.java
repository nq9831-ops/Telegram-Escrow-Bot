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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

/**
 * TON Pay 支付引用登记（ET-31）：前端 {@code createTonPayTransfer} 拿到
 * {@code {reference, bodyBase64Hash}} 后回传登记——webhook 的第 3 步（reference 匹配订单）
 * 依赖它；没有登记面，webhook 收到支付也不知道属于哪单。
 *
 * <p><b>仅买方可登记</b>：TON Pay 在本项目用于「买方锁资」——卖方登记没有对应语义。
 * 鉴权同其余 Web API（initData 验签）。
 */
@RestController
@RequestMapping("/api/tonpay")
public class TonPayReferenceController {

    /** 登记请求（bodyBase64Hash 可空——仅追踪用，不参与结算判定）。 */
    public record ReferenceRequest(String initData, Long orderId, String reference,
                                   String bodyBase64Hash) {
    }

    private final WebAppInitDataVerifier verifier;
    private final EscrowOrderLookupPort orders;
    private final TonPayReferencePort references;

    public TonPayReferenceController(WebAppInitDataVerifier verifier,
                                     EscrowOrderLookupPort orders,
                                     TonPayReferencePort references) {
        if (verifier == null || orders == null || references == null) {
            throw new com.tg.escrow.common.TggException(
                    "TON Pay 登记：验签器、订单查询与引用端口均不可为空");
        }
        this.verifier = verifier;
        this.orders = orders;
        this.references = references;
    }

    @PostMapping("/reference")
    public ResponseEntity<Map<String, Object>> register(
            @RequestBody(required = false) ReferenceRequest req) {
        if (req == null) {
            return fail(HttpStatus.BAD_REQUEST, "缺少请求体");
        }
        Optional<Long> me = verifier.verifyUserId(req.initData());
        if (me.isEmpty()) {
            return fail(HttpStatus.UNAUTHORIZED, "身份校验失败：initData 无效或已过期，请重新打开 Mini App");
        }
        if (req.orderId() == null) {
            return fail(HttpStatus.BAD_REQUEST, "缺少订单号（orderId）");
        }
        if (req.reference() == null || req.reference().isBlank()) {
            return fail(HttpStatus.BAD_REQUEST, "缺少支付引用（reference）");
        }
        EscrowOrder order = orders.byId(req.orderId()).orElse(null);
        if (order == null) {
            return fail(HttpStatus.BAD_REQUEST, "订单 #" + req.orderId() + " 不存在");
        }
        if (order.getBuyerUserId() != me.get()) {
            return fail(HttpStatus.FORBIDDEN, "只有买方可以登记本单的支付引用");
        }
        references.register(order.getId(), req.reference(), req.bodyBase64Hash());
        return ResponseEntity.ok(Map.of("ok", true));
    }

    private static ResponseEntity<Map<String, Object>> fail(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("ok", false, "error", message));
    }
}
