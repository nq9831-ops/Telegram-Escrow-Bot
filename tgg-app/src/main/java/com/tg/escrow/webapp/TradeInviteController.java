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

import com.tg.escrow.NotificationOutcome;
import com.tg.escrow.TradeEvent;
import com.tg.escrow.TradeNotifier;

import com.tg.escrow.InviteLink;
import com.tg.escrow.common.EscrowException;
import com.tg.escrow.common.TggException;
import com.tg.escrow.escrow.AmountTierPolicy;
import com.tg.escrow.escrow.ConcurrentOrderUpdateException;
import com.tg.escrow.escrow.EscrowOrder;
import com.tg.escrow.escrow.RiskPrompt;
import com.tg.escrow.escrow.TradeAdmissionDecision;
import com.tg.escrow.escrow.TradeInvite;
import com.tg.escrow.escrow.TradeInviteCreationResult;
import com.tg.escrow.escrow.TradeInviteException;
import com.tg.escrow.escrow.TradeInviteService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Mini App 深链邀请 API（S7 / Wave 2）——建邀请与接单两个端点。
 *
 * <h2>身份与令牌同源</h2>
 * <p>两个端点的身份都取自 {@link WebAppInitDataVerifier} 的验签结果，<b>请求体里没有
 * userId 字段</b>；接单的令牌还额外来自同一串签名里的 {@code start_param}——因此
 * 「谁在接单」与「接哪条邀请」出自同一份不可伪造的凭证，前端无需（也无法）伪造。
 *
 * <h2>状态码</h2>
 * <p>沿用 {@link TradeApiController} 的既有约定：HTTP 200 + body 内 {@code status} 承载语义
 * （401 验签失败 / 400 参数非法 / 404 邀请不存在 / 409 已被接受或发起方不可承接 / 410 已过期）。
 */
@RestController
@RequestMapping(TradeApiController.BASE_PATH)
public class TradeInviteController {

    private final WebAppInitDataVerifier verifier;
    private final TradeInviteService inviteService;
    private final AmountTierPolicy tierPolicy;
    private final InviteLink inviteLink;
    private final TradeNotifier notifier;

    public TradeInviteController(WebAppInitDataVerifier verifier, TradeInviteService inviteService,
                                 AmountTierPolicy tierPolicy, InviteLink inviteLink,
                                 TradeNotifier notifier) {
        if (verifier == null || inviteService == null || tierPolicy == null || inviteLink == null) {
            throw new TggException("邀请 API：验签器、邀请服务、金额分层策略与邀请链接构造器均不可为空");
        }
        if (notifier == null) {
            throw new TggException("邀请 API：通知器不可为空");
        }
        this.verifier = verifier;
        this.inviteService = inviteService;
        this.tierPolicy = tierPolicy;
        this.inviteLink = inviteLink;
        this.notifier = notifier;
    }

    /**
     * 建一条待接受邀请，返回可转发给对方的深链。
     *
     * @param req 表单载荷（{@code initData} 为 Telegram 签发的原始串）
     * @return {@code {ok:true, inviteUrl, inviteToken, expiresAt, amount, currency, riskPrompt}}
     *         或 {@code {ok:false, status, error}}
     */
    @PostMapping("/invite")
    public Map<String, Object> invite(@RequestBody InviteRequest req) {
        if (req == null) {
            return fail(400, "请求体为空");
        }
        Optional<WebAppInitDataVerifier.VerifiedUser> who = verifier.verify(req.initData());
        if (who.isEmpty()) {
            return fail(401, "身份校验失败：initData 无效或已过期，请重新打开 Mini App");
        }

        TradeInviteCreationResult result;
        try {
            result = inviteService.create(who.get().userId(), parseAmount(req.amount()), req.currency());
        } catch (EscrowException ex) {
            return fail(400, ex.getMessage());
        }

        if (result.status() == TradeInviteCreationResult.Status.REJECTED) {
            TradeAdmissionDecision decision = result.decision();
            String retry = decision.retryAfter() == null ? "暂无法预估" : decision.retryAfter().toString();
            return fail(409, "被拒：" + reasonText(decision.reason()) + "，可重试：" + retry);
        }

        TradeInvite invite = result.invite();
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("ok", true);
        ok.put("inviteUrl", inviteLink.forToken(invite.getToken()));
        ok.put("inviteToken", invite.getToken());
        ok.put("expiresAt", invite.getExpiresAt().toString());
        ok.put("amount", invite.getAmount().toPlainString());
        ok.put("currency", invite.getCurrency());
        ok.put("riskPrompt", RiskPrompt.forAmount(invite.getAmount(), tierPolicy));
        return ok;
    }

    /**
     * 接受一条邀请（令牌取自已验签的 {@code start_param}）→ 物化订单。
     *
     * @param req 载荷（仅 {@code initData}；令牌在签名串里，无需也不应单独传）
     * @return {@code {ok:true, orderId, notified, amount, currency}} 或 {@code {ok:false, status, error}}
     *         ——{@code notified} 表示「已把这次接单告知发起方（买方）」；对方未与机器人会话过时为
     *         {@code false}（接单本身仍成功）
     */
    @PostMapping("/accept")
    public Map<String, Object> accept(@RequestBody AcceptRequest req) {
        if (req == null) {
            return fail(400, "请求体为空");
        }
        Optional<WebAppInitDataVerifier.VerifiedUser> who = verifier.verify(req.initData());
        if (who.isEmpty()) {
            return fail(401, "身份校验失败：initData 无效或已过期，请重新打开 Mini App");
        }
        String token = who.get().startParam();
        if (token == null || token.isBlank()) {
            return fail(400, "缺少邀请令牌：请通过邀请链接打开本页");
        }

        EscrowOrder order;
        try {
            order = inviteService.accept(token, who.get().userId());
        } catch (ConcurrentOrderUpdateException ex) {
            // 并发抢接：邀请已被他人接受（乐观锁兜底）——与「已接受」同义给用户
            return fail(409, "该邀请已被他人接受");
        } catch (TradeInviteException ex) {
            return fail(statusFor(ex.reason()), ex.getMessage());
        } catch (EscrowException ex) {
            return fail(400, ex.getMessage());
        }

        // 订单已物化，此时才通知发起方（买方）；发不出去也不影响接单结果
        NotificationOutcome outcome = notifier.notify(order, who.get().userId(), TradeEvent.ACCEPTED);
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("ok", true);
        ok.put("orderId", order.getId());
        ok.put("notified", outcome == NotificationOutcome.SENT);
        ok.put("amount", order.getAmount().toPlainString());
        ok.put("currency", order.getCurrency());
        return ok;
    }

    /** 拒绝原因 → 状态码。 */
    private static int statusFor(TradeInviteException.Reason reason) {
        return switch (reason) {
            case NOT_FOUND -> 404;
            case ALREADY_ACCEPTED, NOT_ADMITTED -> 409;
            case EXPIRED -> 410;
            case SELF_ACCEPT -> 400;
        };
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
            throw new EscrowException("邀请创建：金额未提供");
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException ex) {
            throw new EscrowException("邀请创建：金额格式非法（" + raw + "）");
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

    /** 建邀请载荷（**刻意不含 userId/买方字段**——身份只能来自验签）。 */
    public record InviteRequest(String initData, String amount, String currency) {
    }

    /** 接单载荷（**只有 initData**——令牌在签名串的 start_param 里）。 */
    public record AcceptRequest(String initData) {
    }
}
