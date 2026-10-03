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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Admin API（S6）：紧急暂停 / 解除暂停（ET-21）——合约治理动作的操作面。
 *
 * <h2>为什么不做多签（与升级端点的差异及理由）</h2>
 * <p>升级换码走联邦多签（{@code EscrowUpgradeService}——「升级权必须多签」硬约束，换码是
 * 终极权力）；暂停**可逆且只收紧**（方向在安全侧），且是**止血动作**——多签协调两方会把
 * 紧急响应拖慢。防护为两道：HTTP Basic（{@code tgg.admin.*}，未配置一律 401）+ 联邦钱包
 * 签名（合约侧强制 {@code sender == federation}）。如日后需要更严，按升级范式加多签门即可。
 *
 * <h2>写链栈未接线</h2>
 * <p>{@code tgg.chain.upgrade.wallet-*} 未配置时 {@link ChainGateway} 无发送器——本端点返回
 * <b>503</b>（fail-closed：「发不出去」绝不回报成成功）。
 */
@RestController
@RequestMapping("/admin/pause")
public class AdminPauseController {

    /** 动作请求：目标合约地址（每次显式传——与 /admin/upgrade 一致）。 */
    public record PauseActionRequest(String contractAddress) {
    }

    private final ChainGateway chain;

    public AdminPauseController(ChainGateway chain) {
        if (chain == null) {
            throw new com.tg.escrow.common.TggException("Admin API：链网关不可为空");
        }
        this.chain = chain;
    }

    /** 紧急暂停（ET-21）：冻结资金与状态推进（治理/存证不拦）。 */
    @PostMapping
    public ResponseEntity<Map<String, Object>> pause(
            @RequestBody(required = false) PauseActionRequest req) {
        return execute(req, "已发送暂停消息", chain::pause);
    }

    /** 解除暂停（ET-21）：恢复资金与状态推进。 */
    @PostMapping("/unpause")
    public ResponseEntity<Map<String, Object>> unpause(
            @RequestBody(required = false) PauseActionRequest req) {
        return execute(req, "已发送解除暂停消息", chain::unpause);
    }

    private ResponseEntity<Map<String, Object>> execute(PauseActionRequest req, String okMessage,
                                                        java.util.function.Consumer<String> action) {
        if (req == null || req.contractAddress() == null || req.contractAddress().isBlank()) {
            return fail(HttpStatus.BAD_REQUEST, "缺少合约地址");
        }
        try {
            action.accept(req.contractAddress().trim());
        } catch (ChainUnavailableException ex) {
            return fail(HttpStatus.SERVICE_UNAVAILABLE, "写链栈未接线：" + ex.getMessage());
        }
        return ResponseEntity.ok(Map.of("ok", true, "message", okMessage));
    }

    private static ResponseEntity<Map<String, Object>> fail(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("ok", false, "error", message));
    }
}
