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
import com.tg.escrow.escrow.TradeStatusView;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 交易状态查询（ET-02 的 Web 形态）——Mini App「订单状态」页的数据源。
 *
 * <p><b>文案同源</b>：复用域视图 {@link TradeStatusView}（与 Bot 的 {@code /escrow status}
 * 同一条口径）——两个出口不分叉。金额以精确字符串出参（机器出口惯例，同裁决/台账 API）。
 *
 * <p><b>鉴权与可见性</b>：initData 验签 + <b>当事方限定</b>——只有买卖双方可查自己的单
 * （订单号是递增数字，不做当事方校验等于全库可枚举）。
 */
@RestController
@RequestMapping(TradeApiController.BASE_PATH)
public class TradeStatusController {

    /** 查询请求。 */
    public record StatusRequest(String initData, Long orderId) {
    }

    private final WebAppInitDataVerifier verifier;
    private final EscrowOrderLookupPort orders;

    public TradeStatusController(WebAppInitDataVerifier verifier, EscrowOrderLookupPort orders) {
        if (verifier == null || orders == null) {
            throw new com.tg.escrow.common.TggException("交易状态 API：验签器与订单查询均不可为空");
        }
        this.verifier = verifier;
        this.orders = orders;
    }

    @PostMapping("/status")
    public ResponseEntity<Map<String, Object>> status(
            @RequestBody(required = false) StatusRequest req) {
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
        EscrowOrder order = orders.byId(req.orderId()).orElse(null);
        if (order == null) {
            return fail(HttpStatus.NOT_FOUND, "订单 #" + req.orderId() + " 不存在");
        }
        if (order.getBuyerUserId() != me.get() && order.getSellerUserId() != me.get()) {
            return fail(HttpStatus.FORBIDDEN, "只能查询自己参与的订单");
        }
        TradeStatusView view = TradeStatusView.of(order.currentState());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("orderId", order.getId());
        body.put("state", view.state().name());
        body.put("stage", view.stage().name());
        body.put("summary", view.summary());
        body.put("nextStep", view.nextStep());
        body.put("amount", order.getAmount().toPlainString());
        body.put("currency", order.getCurrency());
        return ResponseEntity.ok(body);
    }

    private static ResponseEntity<Map<String, Object>> fail(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("ok", false, "error", message));
    }
}
