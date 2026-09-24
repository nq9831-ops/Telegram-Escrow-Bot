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

import com.tg.escrow.common.EscrowException;
import com.tg.escrow.common.TggException;
import com.tg.escrow.core.BotCommand;
import com.tg.escrow.core.CommandActor;
import com.tg.escrow.escrow.AmountTierPolicy;
import com.tg.escrow.escrow.ConcurrentOrderUpdateException;
import com.tg.escrow.escrow.EscrowOrder;
import com.tg.escrow.escrow.EscrowOrderLookupPort;
import com.tg.escrow.escrow.EscrowTradeService;
import com.tg.escrow.escrow.PendingTradeRegistry;
import com.tg.escrow.escrow.RiskPrompt;
import com.tg.escrow.escrow.TradeAdmissionDecision;
import com.tg.escrow.escrow.TradeInitiationRequest;
import com.tg.escrow.escrow.TradeInitiationResult;
import com.tg.escrow.escrow.TradeInvite;
import com.tg.escrow.escrow.TradeInviteCreationResult;
import com.tg.escrow.escrow.TradeInviteService;
import com.tg.escrow.escrow.TradeStatusView;

import java.math.BigDecimal;

/**
 * 交易命令处理器（S2 / T1 收口）——两步流：<b>create 预览风险提示（ET-34），confirm 校验前置后落单</b>。
 *
 * <h2>为什么两步、且 confirm 必须命中前置</h2>
 * <p>ET-34 要求"创建交易前<b>强制</b>展示风险提示"。仅靠文案不够——用户若直接发
 * {@code confirm}，就跳过了与自己金额相关的那条提示（手滑/记错场景）。因此 {@code create}
 * 把请求登记进 {@link PendingTradeRegistry}，{@code confirm} 必须命中同一笔登记才落单；
 * 未预览、参数已改或登记过期，一律退回"先预览"。
 *
 * <h2>被拒回执</h2>
 * <p>含拒绝原因与可重试时刻（无法预估时明说"暂无法预估"）——被拒是正常业务结果，
 * 用户必须知道"为什么"和"何时能再来"。
 *
 * <h2>异常防护对称</h2>
 * <p>{@code create} 与 {@code confirm} 都先把参数装配成 {@link TradeInitiationRequest}
 * （自交易、非正金额等事实错误在此拦下），两侧的失败一律转成"无法创建：&lt;原因&gt;"，
 * 不让底层异常冒成无回执。
 */
public final class TradeCommandHandler {

    private static final String COMMAND = "escrow";
    private static final String SUB_INVITE = "invite";
    private static final String SUB_CREATE = "create";
    private static final String SUB_CONFIRM = "confirm";
    private static final String SUB_STATUS = "status";
    private static final String SUB_CANCEL = "cancel";
    /**
     * 用法说明文案（公开，供分发器判定"这条回执是用法说明"——从而在胶水层附上打开表单的按钮）。
     */
    public static final String USAGE = "用法：/escrow invite <金额> <币种> 生成邀请链接（对方点开即接单）；"
            + "/escrow create <卖方ID> <金额> <币种> 预览风险；"
            + "确认后发 /escrow confirm <卖方ID> <金额> <币种> 创建交易；"
            + "/escrow status <订单号> 查询订单状态；"
            + "/escrow cancel <订单号> 取消订单（仅当事人，且资金未锁仓时）";

    private final EscrowTradeService service;
    private final AmountTierPolicy tierPolicy;
    private final PendingTradeRegistry pending;
    private final EscrowOrderLookupPort lookup;
    private final TradeInviteService inviteService;
    private final InviteLink inviteLink;

    public TradeCommandHandler(EscrowTradeService service, AmountTierPolicy tierPolicy,
                               PendingTradeRegistry pending, EscrowOrderLookupPort lookup,
                               TradeInviteService inviteService, InviteLink inviteLink) {
        if (service == null || tierPolicy == null || pending == null || lookup == null) {
            throw new TggException("命令处理：交易服务、金额分层策略、待确认登记与订单查询端口均不可为空");
        }
        if (inviteService == null || inviteLink == null) {
            throw new TggException("命令处理：邀请服务与邀请链接构造器均不可为空");
        }
        this.service = service;
        this.tierPolicy = tierPolicy;
        this.pending = pending;
        this.lookup = lookup;
        this.inviteService = inviteService;
        this.inviteLink = inviteLink;
    }

    /** 本处理器是否管辖该命令。 */
    public boolean canHandle(BotCommand cmd) {
        return cmd != null && COMMAND.equals(cmd.name());
    }

    /**
     * 处理 escrow 命令并返回回执文案。
     *
     * @param cmd   已解析命令（须由 {@link #canHandle} 判定为管辖）
     * @param actor 执行者（发起人 = 买方）
     * @return 用户可读回执；参数缺失/非法时返回用法说明
     * @throws TggException 命令或执行者缺失、或命令不归本处理器管辖
     */
    public String handle(BotCommand cmd, CommandActor actor) {
        if (cmd == null || actor == null) {
            throw new TggException("命令处理：命令与执行者不可为空");
        }
        if (!canHandle(cmd)) {
            throw new TggException("命令处理：不处理的命令 " + cmd.name());
        }

        String sub = cmd.argOpt(0).orElse(null);

        // T2 状态查询 / 取消：都只吃订单号，不走 create/confirm 那套买卖双方的参数解析
        if (SUB_STATUS.equals(sub)) {
            return handleStatus(cmd);
        }
        if (SUB_CANCEL.equals(sub)) {
            return handleCancel(cmd, actor);
        }
        if (SUB_INVITE.equals(sub)) {
            return handleInvite(cmd, actor);
        }

        if (!SUB_CREATE.equals(sub) && !SUB_CONFIRM.equals(sub)) {
            return USAGE;
        }

        Long sellerId = parseLong(cmd.argOpt(1).orElse(null));
        BigDecimal amount = parseAmount(cmd.argOpt(2).orElse(null));
        String currency = cmd.argOpt(3).orElse(null);
        if (sellerId == null || amount == null || currency == null || currency.isBlank()) {
            return USAGE;
        }

        // 参数先装配成请求：自交易 / 非正金额等事实错误在两侧一致地被拦下（异常防护对称）
        TradeInitiationRequest request;
        try {
            request = new TradeInitiationRequest(actor.userId(), sellerId, amount, currency);
        } catch (EscrowException ex) {
            return "无法创建：" + ex.getMessage();
        }

        if (SUB_CREATE.equals(sub)) {
            // ET-34：创建前强制展示风险提示——登记待确认请求，本分支不落单
            pending.prepare(request);
            String prompt = RiskPrompt.forAmount(request.amount(), tierPolicy);
            return "⚠️ 交易前风险提示：" + prompt
                    + "\n确认无误请发：/escrow confirm " + request.sellerId() + " "
                    + request.amount() + " " + request.currency();
        }

        if (pending.consume(request).isEmpty()) {
            // 未预览 / 参数已改 / 登记过期：风险提示不可绕过
            return "请先预览风险提示再确认（未预览、参数已变或超过有效期）：\n" + USAGE;
        }

        TradeInitiationResult result;
        try {
            result = service.initiate(request);
        } catch (EscrowException ex) {
            return "无法创建：" + ex.getMessage();
        }

        if (result.status() == TradeInitiationResult.Status.CREATED) {
            return "已创建订单 #" + result.order().getId();
        }

        TradeAdmissionDecision decision = result.decision();
        String retry = decision.retryAfter() == null
                ? "暂无法预估"
                : decision.retryAfter().toString();
        return "被拒：" + reasonText(decision.reason()) + "，可重试：" + retry;
    }

    /**
     * 深链邀请：建一条待接受邀请，回执带可转发给对方的直链。
     *
     * <p>被拒是<b>正常业务结果</b>（回原因与可重试时刻）；参数非法（外币种/非正金额/缺参）回用法。
     * 建邀请阶段<b>不物化订单</b>——订单只会在对方点开链接接单时产生。
     */
    private String handleInvite(BotCommand cmd, CommandActor actor) {
        BigDecimal amount = parseAmount(cmd.argOpt(1).orElse(null));
        String currency = cmd.argOpt(2).orElse(null);
        if (amount == null || currency == null || currency.isBlank()) {
            return USAGE;
        }

        TradeInviteCreationResult result;
        try {
            result = inviteService.create(actor.userId(), amount, currency);
        } catch (EscrowException ex) {
            return "无法创建邀请：" + ex.getMessage();
        }

        if (result.status() == TradeInviteCreationResult.Status.REJECTED) {
            TradeAdmissionDecision decision = result.decision();
            String retry = decision.retryAfter() == null
                    ? "暂无法预估"
                    : decision.retryAfter().toString();
            return "被拒：" + reasonText(decision.reason()) + "，可重试：" + retry;
        }

        TradeInvite invite = result.invite();
        return "已创建待接受邀请（" + invite.getAmount().toPlainString() + " " + invite.getCurrency()
                + "，有效期至 " + invite.getExpiresAt() + "）\n"
                + "把下面链接发给对方，对方点开即自动接单：\n"
                + inviteLink.forToken(invite.getToken());
    }

    /**
     * T2 状态查询：订单号 → 用户可读状态视图。
     *
     * <p>查不到<b>不是错误</b>——按用户视角回「不存在」，而不是抛异常或空响应。
     * 视图文案本身不含买卖双方 ID 与金额（{@link TradeStatusView} 的既定约束），
     * 因此可安全地回在群聊里。
     */
    private String handleStatus(BotCommand cmd) {
        Long orderId = parseLong(cmd.argOpt(1).orElse(null));
        if (orderId == null) {
            return USAGE;
        }
        return lookup.byId(orderId)
                .map(EscrowOrder::currentState)
                .map(TradeStatusView::of)
                .map(view -> "订单 #" + orderId + "：" + view.summary()
                        + "\n下一步：" + view.nextStep())
                .orElse("订单 #" + orderId + " 不存在（请核对订单号）");
    }

    /**
     * 取消订单：查单 → 交服务层做权限与状态校验 → 落库。
     *
     * <p>权限与状态守卫都在服务层（{@link EscrowTradeService#cancel}）——命令层只把
     * 「订单不存在」「无权」「状态不允许」翻译成用户能懂的文案，不做第二套判定，
     * 免得两处规则漂移。
     */
    private String handleCancel(BotCommand cmd, CommandActor actor) {
        Long orderId = parseLong(cmd.argOpt(1).orElse(null));
        if (orderId == null) {
            return USAGE;
        }
        EscrowOrder order = lookup.byId(orderId).orElse(null);
        if (order == null) {
            return "订单 #" + orderId + " 不存在（请核对订单号）";
        }
        try {
            service.cancel(order, actor.userId());
        } catch (ConcurrentOrderUpdateException ex) {
            // 并发冲突与"无权限/状态不符"是两回事：前者重查后重试有意义，不能笼统说"无法取消"
            return "订单 #" + orderId + " 已被他人变更，请重查后再操作";
        } catch (EscrowException ex) {
            return "无法取消：" + ex.getMessage();
        }
        return "订单 #" + orderId + " 已取消";
    }

    private static String reasonText(TradeAdmissionDecision.Reason reason) {
        return switch (reason) {
            case CONCURRENT_LIMIT -> "已有进行中的交易";
            case COOLDOWN -> "冷却期未满";
            case DISPUTE_HOLD -> "存在未决争议";
            case ALLOWED -> "放行";
        };
    }

    private static Long parseLong(String s) {
        if (s == null) {
            return null;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static BigDecimal parseAmount(String s) {
        if (s == null) {
            return null;
        }
        try {
            return new BigDecimal(s.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
