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
import com.tg.escrow.chain.UpgradeStatus;
import com.tg.escrow.common.EscrowException;
import com.tg.escrow.escrow.EscrowMultiSigRule.Party;
import com.tg.escrow.escrow.EscrowUpgradeService;
import com.tg.escrow.escrow.PartySignatureVerifier;
import com.tg.escrow.escrow.UpgradeApproval;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.commons.codec.binary.Hex;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.ton.ton4j.cell.Cell;

/**
 * 合约升级操作面（⑥ 遗留修复）：联邦运维方提交升级裁决单 + 签名集，链下多签守卫在前、
 * 链上执行在后——与 {@code AdminVerdictController} 同一纪律。
 *
 * <h2>回执诚实性（与 {@code CHAIN_CAVEAT} 同一纪律）</h2>
 * <p>成功回执必带 {@code chainExecuted} 与 {@code chainNote}——多签业务成立<b>不等于</b>
 * 消息已上链。写链栈未接线（seed 未配置）或广播失败时 {@code chainExecuted} 恒
 * {@code false} 并如实写明原因，绝不假装已升级。
 *
 * <h2>失败码契约（计划「操作面契约」定案）</h2>
 * <p>请求非法（坏 base64/hex/缺字段）与守卫拒绝（EscrowException）→ 400；status 查询失败
 * → 502（查询类语义，对标 AdminChainController 的 provider 不可用）；链上执行未果不占
 * 失败码——走 200 + {@code chainExecuted=false}（业务已成立，如实回报）。
 *
 * <h2>安全</h2>
 * <p>鉴权走 {@code /admin/**} 通配安全层（SecurityConfig，未配置凭据即 401 fail-closed）。
 * 升级 = 换合约代码的最高权力，链下多签（EscrowUpgradeService，≥2 方且必含联邦）是第一道门。
 */
@RestController
@RequestMapping("/admin/upgrade")
public class AdminUpgradeController {

    private final EscrowUpgradeService upgradeService;
    private final PartySignatureVerifier signatureVerifier;
    private final ChainGateway chain;

    public AdminUpgradeController(EscrowUpgradeService upgradeService,
                                  PartySignatureVerifier signatureVerifier,
                                  ChainGateway chain) {
        if (upgradeService == null || signatureVerifier == null || chain == null) {
            throw new IllegalStateException("升级操作面缺少依赖（编排/验签器/链上入口均不可空）");
        }
        this.upgradeService = upgradeService;
        this.signatureVerifier = signatureVerifier;
        this.chain = chain;
    }

    /** propose 请求体：newCodeBoc = 新代码 cell 的 base64 boc。 */
    public record UpgradeProposeRequest(String contractAddress, String newCodeBoc,
                                        Map<String, String> signatures, long issuedAt) {
    }

    /** apply/cancel 请求体：codeHashHex = 被操作提案的新代码 hash（四维防挪用的绑定面）。 */
    public record UpgradeActionRequest(String contractAddress, String codeHashHex,
                                       Map<String, String> signatures, long issuedAt) {
    }

    @PostMapping("/propose")
    public ResponseEntity<Map<String, Object>> propose(@RequestBody UpgradeProposeRequest req) {
        if (req == null) {
            return fail(HttpStatus.BAD_REQUEST, "缺少升级请求体");
        }
        if (isBlank(req.contractAddress())) {
            return fail(HttpStatus.BAD_REQUEST, "缺少合约地址");
        }
        byte[] boc;
        try {
            boc = java.util.Base64.getDecoder().decode(
                    req.newCodeBoc() == null ? "" : req.newCodeBoc().trim());
        } catch (IllegalArgumentException ex) {
            return fail(HttpStatus.BAD_REQUEST, "newCodeBoc 不是合法 base64");
        }
        Cell newCode;
        try {
            // ton4j Cell.fromBoc 对非法 boc 抛裸 java.lang.Error——Exception 之外必须兜 Error
            newCode = Cell.fromBoc(boc);
        } catch (Exception | Error ex) {
            return fail(HttpStatus.BAD_REQUEST, "newCodeBoc 不是合法 boc");
        }
        String codeHashHex = Hex.encodeHexString(newCode.hash());
        return execute(UpgradeApproval.Action.PROPOSE, req.contractAddress(), codeHashHex,
                req.signatures(), req.issuedAt(),
                addr -> chain.proposeUpgrade(addr, newCode));
    }

    @PostMapping("/apply")
    public ResponseEntity<Map<String, Object>> apply(@RequestBody UpgradeActionRequest req) {
        return executeAction(UpgradeApproval.Action.APPLY, req, chain::applyUpgrade);
    }

    @PostMapping("/cancel")
    public ResponseEntity<Map<String, Object>> cancel(@RequestBody UpgradeActionRequest req) {
        return executeAction(UpgradeApproval.Action.CANCEL, req, chain::cancelUpgrade);
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status(@RequestParam("contract") String contractAddress) {
        if (isBlank(contractAddress)) {
            return fail(HttpStatus.BAD_REQUEST, "缺少合约地址");
        }
        UpgradeStatus upgradeStatus;
        try {
            upgradeStatus = chain.upgradeStatus(contractAddress.trim());
        } catch (ChainUnavailableException ex) {
            // 查询类失败：502（对标 AdminChainController 的 provider 不可用）
            return fail(HttpStatus.BAD_GATEWAY, ex.getMessage());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("contractAddress", contractAddress.trim());
        body.put("codeHashHex", String.format("%064x", upgradeStatus.codeHash()));
        body.put("proposedAt", upgradeStatus.proposedAt());
        body.put("hasProposal", upgradeStatus.hasProposal());
        return ResponseEntity.ok(body);
    }

    private ResponseEntity<Map<String, Object>> executeAction(UpgradeApproval.Action action,
                                                             UpgradeActionRequest req,
                                                             ChainCall call) {
        if (req == null) {
            return fail(HttpStatus.BAD_REQUEST, "缺少升级请求体");
        }
        if (isBlank(req.contractAddress()) || isBlank(req.codeHashHex())) {
            return fail(HttpStatus.BAD_REQUEST, "缺少合约地址或 codeHashHex");
        }
        return execute(action, req.contractAddress(), req.codeHashHex().trim(),
                req.signatures(), req.issuedAt(), call);
    }

    /** 守卫 → 链上执行 → 如实回报（三段共用）。 */
    private ResponseEntity<Map<String, Object>> execute(UpgradeApproval.Action action,
                                                        String contractAddress,
                                                        String codeHashHex,
                                                        Map<String, String> rawSignatures,
                                                        long issuedAt,
                                                        ChainCall call) {
        Map<Party, byte[]> signatures;
        try {
            signatures = parseSignatures(rawSignatures);
        } catch (IllegalArgumentException ex) {
            return fail(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
        try {
            UpgradeApproval approval = new UpgradeApproval(contractAddress.trim(), action,
                    codeHashHex, issuedAt);
            // issuedAt 随请求携带（EscrowVerdict 的 issuedAt 缺陷同款防线）：签名方与服务端
            // 共用同一裁决单时刻，服务端不得以接收时刻重建载荷
            upgradeService.verifiedForExecution(approval, signatures, signatureVerifier);
        } catch (EscrowException ex) {
            return fail(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
        boolean chainExecuted;
        String chainNote;
        try {
            call.send(contractAddress.trim());
            chainExecuted = true;
            chainNote = "已提交链上执行";
        } catch (ChainUnavailableException ex) {
            chainExecuted = false;
            chainNote = ex.getMessage();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("action", action.name());
        body.put("contractAddress", contractAddress.trim());
        body.put("codeHashHex", codeHashHex);
        body.put("chainExecuted", chainExecuted);
        body.put("chainNote", chainNote);
        return ResponseEntity.ok(body);
    }

    @FunctionalInterface
    private interface ChainCall {
        void send(String contractAddress) throws ChainUnavailableException;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
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
