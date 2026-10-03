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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tg.escrow.escrow.EscrowOrder;
import com.tg.escrow.escrow.EscrowOrderLookupPort;
import com.tg.escrow.escrow.TonPayReferencePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Map;

/**
 * TON Pay webhook（ET-31/32）——支付完成通知的六步验证（03 文档 §1.2）：
 * ① 验签 → ② 只处理 {@code transfer.completed} → ③ reference 匹配订单 →
 * ④ 防重复 → ⑤ 金额/币种核对 → ⑥ 结算（记 txHash）。
 *
 * <h2>fail-closed 三态</h2>
 * <ul>
 *   <li><b>未配置</b> {@code tgg.tonpay.api-secret} → <b>503</b>（未启用不是"裸奔"）；</li>
 *   <li>验签失败 → <b>401</b>；报文非法 JSON → 400；</li>
 *   <li>其余业务分支一律 <b>200</b>（webhook 语义：200 = 已受理勿重试）。包括
 *       "未知 reference"与"金额不符"——前者可能是测试支付，后者是数据问题；
 *       两者都<b>不结算</b>（安全优先：绝不按"差不多"入账）并留 warn 日志。</li>
 * </ul>
 *
 * <h2>待真机核销的两处口径</h2>
 * <p>① 签名原文拼接（见 {@link TonPayWebhookVerifier} 类注释）；② 报文<b>字段名</b>
 * （{@code event/reference/amount/asset/txHash}——03 文档未给 payload schema，按合理
 * 扁平形态实现）。拿到 API Key 后按真实报文校准，两处都在本文件/验签器内一改即可。
 */
@RestController
@RequestMapping("/api/tonpay")
public class TonPayWebhookController {

    private static final Logger log = LoggerFactory.getLogger(TonPayWebhookController.class);

    private final TonPayReferencePort references;
    private final EscrowOrderLookupPort orders;
    private final ObjectMapper mapper;
    private final Clock clock;
    /** null = 未配置 api-secret（端点 503）。 */
    private final TonPayWebhookVerifier verifier;

    public TonPayWebhookController(@Value("${tgg.tonpay.api-secret:}") String apiSecret,
                                   TonPayReferencePort references,
                                   EscrowOrderLookupPort orders,
                                   ObjectMapper mapper,
                                   Clock clock) {
        if (references == null || orders == null || mapper == null || clock == null) {
            throw new com.tg.escrow.common.TggException(
                    "TON Pay webhook：引用端口、订单查询、JSON 解析器与时钟均不可为空");
        }
        this.references = references;
        this.orders = orders;
        this.mapper = mapper;
        this.clock = clock;
        this.verifier = (apiSecret == null || apiSecret.isBlank())
                ? null : new TonPayWebhookVerifier(apiSecret);
    }

    @PostMapping("/webhook")
    public ResponseEntity<Map<String, Object>> webhook(
            @RequestHeader(value = "X-TonPay-Signature", required = false) String signature,
            @RequestBody(required = false) byte[] body) {
        if (verifier == null) {
            return error(HttpStatus.SERVICE_UNAVAILABLE,
                    "TON Pay 未配置（缺少 tgg.tonpay.api-secret）");
        }
        if (!verifier.matches(body, signature)) {
            return error(HttpStatus.UNAUTHORIZED, "验签失败");
        }
        Map<String, Object> payload;
        try {
            payload = mapper.readValue(body, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception ex) {
            return error(HttpStatus.BAD_REQUEST, "报文不是合法 JSON");
        }

        // ② 只处理 transfer.completed
        String event = asText(payload.get("event"));
        if (!"transfer.completed".equals(event)) {
            return ok("忽略事件：" + event + "（仅处理 transfer.completed）");
        }

        // ③ reference 匹配订单
        String reference = asText(payload.get("reference"));
        if (reference == null || reference.isBlank()) {
            return ok("无 reference，忽略");
        }
        // ④ 防重复
        if (references.isSettled(reference)) {
            return ok("重复投递：该笔已结算（幂等忽略）");
        }
        Long orderId = references.resolveOrderId(reference).orElse(null);
        if (orderId == null) {
            log.warn("TON Pay webhook：未知 reference（未登记，不处理）");
            return ok("未知 reference（未登记）");
        }
        EscrowOrder order = orders.byId(orderId).orElse(null);
        if (order == null) {
            log.warn("TON Pay webhook：reference 指向的订单不存在 order={}", orderId);
            return ok("订单不存在（reference 指向 " + orderId + "）");
        }

        // ⑤ 金额/币种核对（绝不按"差不多"入账）
        BigDecimal amount = asDecimal(payload.get("amount"));
        String currency = asText(payload.get("asset"));
        if (amount == null || currency == null
                || amount.compareTo(order.getAmount()) != 0
                || !currency.equalsIgnoreCase(order.getCurrency())) {
            log.warn("TON Pay webhook：金额/币种与订单不符 order={}（未结算）", orderId);
            return ok("金额/币种与订单不符——未结算（请人工核对）");
        }

        // ⑥ 结算（记 txHash；实现幂等——重放不覆盖首笔痕迹）
        references.markSettled(reference, asText(payload.get("txHash")), amount,
                order.getCurrency(), clock.instant());
        return ok("已结算 order=" + orderId);
    }

    private static ResponseEntity<Map<String, Object>> ok(String message) {
        return ResponseEntity.ok(Map.of("ok", true, "message", message));
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("ok", false, "error", message));
    }

    private static String asText(Object value) {
        return value == null ? null : value.toString();
    }

    private static BigDecimal asDecimal(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
