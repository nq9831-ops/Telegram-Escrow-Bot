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

import com.tg.escrow.chain.ChainGateway;
import com.tg.escrow.chain.ChainUnavailableException;
import com.tg.escrow.common.EscrowException;
import com.tg.escrow.escrow.EscrowMultiSigRule.Party;
import com.tg.escrow.escrow.EscrowOrder;
import com.tg.escrow.escrow.EscrowOrderLookupPort;
import com.tg.escrow.escrow.EscrowVerdict;
import com.tg.escrow.escrow.EscrowVerdictService;
import org.apache.commons.codec.binary.Hex;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 裁决入口（S5 · 方案 A）：联邦仲裁方提交裁决单 + 签名集，编排层落状态、链上如实报告。
 *
 * <h2>为什么是 admin API 而不是 Bot 命令</h2>
 * <p>裁决是联邦仲裁节点的职责，不是群内操作；且签名集是结构化数据（不是聊天文本）。
 * 鉴权走既有 {@code /admin/**} 安全层（{@code TGG_ADMIN_*}，未配置则 401 fail-closed）。
 *
 * <h2>回执诚实性（与 {@code CHAIN_CAVEAT} 同一纪律）</h2>
 * <p>成功回执必带 {@code chainExecuted} 与 {@code chainNote}——状态登记成功<b>不等于</b>钱已动。
 * 订单未部署链上合约（或写链栈未接线）时 {@code chainExecuted} 恒 {@code false}，
 * 明说「未发生真实出款」。
 *
 * <h2>签名集不入库存档</h2>
 * <p>{@code escrow_orders} 无承载列（不新增表是非目标）——签名集经 {@link EscrowVerdictService}
 * 验签后留痕于执行日志；本 API 只负责接收、解析（party 名 + hex 签名）与转交。
 */
@RestController
@RequestMapping(AdminVerdictController.BASE_PATH)
public class AdminVerdictController {

    static final String BASE_PATH = "/admin/verdict";

    private final EscrowVerdictService verdictService;
    private final com.tg.escrow.escrow.FeePolicy feePolicy;
    private final EscrowOrderLookupPort lookup;
    private final ChainGateway chain;
    /** 惩罚编排（ET-61）：裁决落定后记一次败诉，并回报败诉方的惩罚计划。 */
    private final com.tg.escrow.escrow.PenaltyService penaltyService;

    /**
     * 裁决请求体。
     *
     * @param orderId    订单号
     * @param outcome    裁决结果：{@code RELEASE}（放款卖方）/ {@code REFUND}（退款买方）
     * @param reason     裁决理由（空串=明确无理由）
     * @param issuedAt   裁决单签发时刻（epoch second，多方共用——签名覆盖 canonicalBytes 含此时刻）
     * @param signatures 参与方 → hex 签名（对裁决单 canonicalBytes 的签名）
     */
    public record VerdictRequest(long orderId, String outcome, String reason,
                                 long issuedAt, Map<String, String> signatures) {
    }

    public AdminVerdictController(EscrowVerdictService verdictService,
                                  EscrowOrderLookupPort lookup,
                                  ChainGateway chain,
                                  com.tg.escrow.escrow.FeePolicy feePolicy,
                                  com.tg.escrow.escrow.PenaltyService penaltyService) {
        this.verdictService = verdictService;
        this.lookup = lookup;
        this.chain = chain;
        if (feePolicy == null) {
            throw new com.tg.escrow.common.TggException("裁决入口：费率策略不可为空");
        }
        if (penaltyService == null) {
            throw new com.tg.escrow.common.TggException("裁决入口：惩罚编排不可为空");
        }
        this.feePolicy = feePolicy;
        this.penaltyService = penaltyService;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> execute(@RequestBody VerdictRequest req) {
        if (req == null) {
            return fail(HttpStatus.BAD_REQUEST, "缺少裁决请求体");
        }
        EscrowVerdict.Outcome outcome;
        try {
            outcome = EscrowVerdict.Outcome.valueOf(req.outcome() == null ? "" : req.outcome().trim());
        } catch (IllegalArgumentException ex) {
            return fail(HttpStatus.BAD_REQUEST, "裁决结果非法（应为 RELEASE 或 REFUND）");
        }
        Map<Party, byte[]> signatures;
        try {
            signatures = parseSignatures(req.signatures());
        } catch (IllegalArgumentException ex) {
            return fail(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
        EscrowOrder order = lookup.byId(req.orderId()).orElse(null);
        if (order == null) {
            return fail(HttpStatus.NOT_FOUND, "订单 #" + req.orderId() + " 不存在");
        }

        EscrowOrder saved;
        try {
            // issuedAt 随请求携带（签名方与服务端共用同一裁决单时刻）：canonicalBytes 含此时刻，
            // 服务端若以接收时刻重建，验签载荷与签名方不一致，Ed25519 必败。时效由 service 守卫。
            EscrowVerdict verdict = new EscrowVerdict(req.orderId(), outcome,
                    req.reason() == null ? "" : req.reason(), Instant.ofEpochSecond(req.issuedAt()));
            saved = verdictService.execute(order, verdict, signatures);
        } catch (EscrowException ex) {
            // 签名不足/状态不符等是正常业务拒绝（400 + 可读原因），不是服务错误
            return fail(HttpStatus.BAD_REQUEST, ex.getMessage());
        }

        // 败诉台账（ET-61）：裁决落定即记一行（幂等——同一订单重复裁决不重复记罚）。
        // 记在入口而非 EscrowVerdictService：后者是"裁决语义"的执行者，惩罚是它的下游消费者。
        com.tg.escrow.escrow.DisputeLoss loss = penaltyService.recordDisputeLoss(saved, outcome);

        boolean chainExecuted;
        String chainNote;
        try {
            // 订单的链上合约地址（V10 列；未部署时为 null——ChainGateway 会以
            // 「缺少托管合约地址」fail-closed，如实报"仅完成状态登记，未发生真实出款"）。
            chain.resolveDispute(saved.getChainContractAddress(),
                    outcome == EscrowVerdict.Outcome.RELEASE);
            chainExecuted = true;
            chainNote = "已提交链上执行";
        } catch (ChainUnavailableException ex) {
            chainExecuted = false;
            chainNote = ex.getMessage();
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("orderId", saved.getId());
        body.put("outcome", outcome.name());
        body.put("state", saved.currentState().name());
        // 费用口径（ET-30 · 三费分离）：仲裁费独立于平台费、联邦管理费从仲裁费分成；
        // 费率 0 时值为 0——API schema 稳定（运维要能区分「没收费」与「没算」）
        body.put("arbitrationFee", feePolicy.arbitrationFee(saved.getAmount()).toPlainString());
        body.put("federationFee", feePolicy.federationFee(saved.getAmount()).toPlainString());
        // 裁决净额（败诉方承担仲裁费后的实收/实退）——与 FeePolicy.netAfterArbitration 同源
        body.put("netAmount", feePolicy.netAfterArbitration(saved.getAmount()).toPlainString());
        // 败诉方惩罚计划（ET-61）——如实回报触发的维度（VOTE_SUSPEND 在本部署无对象，仍会列出）
        body.put("loserPenalties", penaltyService.planOf(loss.getLoserUserId()).stream()
                .map(p -> p.name()).toList());
        body.put("chainExecuted", chainExecuted);
        body.put("chainNote", chainNote);
        return ResponseEntity.ok(body);
    }

    private static Map<Party, byte[]> parseSignatures(Map<String, String> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<Party, byte[]> parsed = new EnumMap<>(Party.class);
        for (Map.Entry<String, String> entry : raw.entrySet()) {
            Party party;
            try {
                party = Party.valueOf(entry.getKey() == null ? "" : entry.getKey().trim());
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("未知参与方：" + entry.getKey());
            }
            String hex = entry.getValue() == null ? "" : entry.getValue().trim();
            try {
                parsed.put(party, Hex.decodeHex(hex.toCharArray()));
            } catch (Exception ex) {
                throw new IllegalArgumentException("参与方 " + entry.getKey() + " 的签名不是合法 hex");
            }
        }
        return parsed;
    }

    private static ResponseEntity<Map<String, Object>> fail(HttpStatus status, String error) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("error", error);
        return ResponseEntity.status(status).body(body);
    }
}
