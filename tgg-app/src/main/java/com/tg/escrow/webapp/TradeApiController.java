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

import com.tg.escrow.common.EscrowException;
import com.tg.escrow.escrow.AmountTierPolicy;
import com.tg.escrow.escrow.EscrowTradeService;
import com.tg.escrow.escrow.RiskPrompt;
import com.tg.escrow.escrow.TradeAdmissionDecision;
import com.tg.escrow.escrow.TradeInitiationRequest;
import com.tg.escrow.escrow.TradeInitiationResult;
import com.tg.escrow.common.TggException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Mini App 交易 API（S6 / ET-07 的 Bot 侧替代入口）。
 *
 * <h2>与命令通道的关系</h2>
 * <p>本控制器的用户来自 <b>Mini App 表单</b>而非 Telegram 命令——因此它<b>不经</b>
 * {@code TradeCommandHandler}（后者返回面向聊天窗口的中文回执字符串，解析它既脆又会在
 * 文案改动时静默失效）。这里直接用 {@link EscrowTradeService} 拿<b>结构化</b>结果，
 * 复用同一套领域守卫（准入三规则 / 状态机 / 金额分层），不重复实现业务判断。
 *
 * <h2>身份只来自验签</h2>
 * <p>买方身份取自 {@link WebAppInitDataVerifier} 的验签结果，<b>请求体里没有 userId 字段</b>
 * ——前端即便伪造也无处可传；改了 {@code initData} 则签名不匹配。验签不过一律 401，
 * <b>绝不落单</b>。
 *
 * <h2>ET-34（风险提示）在表单侧的落法</h2>
 * <p>命令通道用 {@code create → confirm} 两步强制展示提示；Mini App 是单页表单，
 * 由前端在提交按钮上方常驻展示同一条 {@link RiskPrompt} 文案（响应里一并回传），
 * 视为满足"创建前已展示"。<b>不支持跨入口拼单</b>（Bot 上 create 后到 Mini App confirm），
 * 因其风险提示语境不同。
 *
 * <h2>已知简化（诚实标注）</h2>
 * <p>当前统一返回 HTTP 200 + body 内 {@code status} 字段承载语义（如 401/400/409）。
 * 生产化时宜改为 {@code ResponseEntity} 让 HTTP 状态码本身也正确——那会改变前端与
 * 测试的契约，故留有意识地为后续波次。</p>
 */
@RestController
@RequestMapping(TradeApiController.BASE_PATH)
public class TradeApiController {

    /** 交易类端点根路径（与 {@code /admin} 分离——后者语义是后台管理且已被 Nginx 全局 deny）。 */
    public static final String BASE_PATH = "/api/trade";

    private final WebAppInitDataVerifier verifier;
    private final EscrowTradeService service;
    private final AmountTierPolicy tierPolicy;

    public TradeApiController(WebAppInitDataVerifier verifier, EscrowTradeService service,
                              AmountTierPolicy tierPolicy) {
        if (verifier == null || service == null || tierPolicy == null) {
            throw new TggException("交易 API：验签器、交易服务与金额分层策略均不可为空");
        }
        this.verifier = verifier;
        this.service = service;
        this.tierPolicy = tierPolicy;
    }

    /**
     * 创建交易。
     *
     * @param req 表单载荷（{@code initData} 为 Telegram 签发的原始串）
     * @return {@code {ok:true, orderId, riskPrompt}} 或 {@code {ok:false, status, error}}
     */
    @PostMapping("/create")
    public Map<String, Object> create(@RequestBody CreateRequest req) {
        if (req == null) {
            return fail(400, "请求体为空");
        }

        // 1) 身份：验签不过一律拒绝，绝不落单
        Optional<Long> buyerId = verifier.verifyUserId(req.initData());
        if (buyerId.isEmpty()) {
            return fail(401, "身份校验失败：initData 无效或已过期，请重新打开 Mini App");
        }

        // 2) 装配（自交易 / 非正金额等事实错误在此抛）
        TradeInitiationRequest request;
        try {
            request = new TradeInitiationRequest(buyerId.get(), req.sellerId(),
                    parseAmount(req.amount()), req.currency());
        } catch (EscrowException ex) {
            return fail(400, ex.getMessage());
        }

        // 3) 准入与落单（复用领域服务，不重复业务判断）
        TradeInitiationResult result;
        try {
            result = service.initiate(request);
        } catch (EscrowException ex) {
            return fail(400, ex.getMessage());
        }

        if (result.status() == TradeInitiationResult.Status.CREATED) {
            Map<String, Object> ok = new LinkedHashMap<>();
            ok.put("ok", true);
            ok.put("orderId", result.order().getId());
            // 风险提示随响应回传：前端在提交按钮上方常驻展示（ET-34 的单页形态）
            ok.put("riskPrompt", RiskPrompt.forAmount(request.amount(), tierPolicy));
            return ok;
        }

        TradeAdmissionDecision decision = result.decision();
        String retry = decision.retryAfter() == null ? "暂无法预估" : decision.retryAfter().toString();
        return fail(409, "被拒：" + reasonText(decision.reason()) + "，可重试：" + retry);
    }

    private static Map<String, Object> fail(int status, String error) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("status", status);
        body.put("error", error);
        return body;
    }

    private static BigDecimal parseAmount(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new EscrowException("交易创建：金额未提供");
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException ex) {
            throw new EscrowException("交易创建：金额格式非法（" + raw + "）");
        }
    }

    private static String reasonText(TradeAdmissionDecision.Reason reason) {
        return switch (reason) {
            case CONCURRENT_LIMIT -> "已有进行中的交易";
            case COOLDOWN -> "冷却期未满";
            case DISPUTE_HOLD -> "存在未决争议";
            case ALLOWED -> "放行";
        };
    }

    /** 表单载荷（**刻意不含 userId**——身份只能来自验签）。 */
    public record CreateRequest(String initData, long sellerId, String amount, String currency) {
    }
}
