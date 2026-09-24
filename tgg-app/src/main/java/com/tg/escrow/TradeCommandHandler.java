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
import com.tg.escrow.escrow.EscrowTradeService;
import com.tg.escrow.escrow.TradeAdmissionDecision;
import com.tg.escrow.escrow.TradeInitiationRequest;
import com.tg.escrow.escrow.TradeInitiationResult;

import java.math.BigDecimal;

/**
 * 交易命令处理器（原文档 S2 / T1 收口）——把解析后的命令接到 {@link EscrowTradeService}。
 *
 * <h2>它补的缺口</h2>
 * <p>{@link EscrowTradeService} 此前没有任何生产调用方——一条完整的业务路径
 * （解析 → 鉴权 → 创建 → 回执）缺了"创建→回执"这一环。本类把 {@code /escrow create}
 * 接上服务，并负责把结果翻译成<b>用户能读的中文回执</b>。
 *
 * <h2>为什么回执要带"为什么"和"何时能再来"</h2>
 * <p>被拒是正常业务结果。只说"不行"会让用户反复重试并误以为系统故障——
 * 所以回执必须含拒绝原因与可重试时刻（无法预估时明说"暂无法预估"）。
 *
 * <h2>只管 escrow</h2>
 * <p>{@link #canHandle} 判定是否由本处理器管辖；{@link #handle} 只处理 escrow 命令。
 * 分发器据此把命令路由到对应处理器。
 */
public final class TradeCommandHandler {

    private static final String COMMAND = "escrow";
    private static final String SUB_CREATE = "create";
    private static final String USAGE = "用法：/escrow create <卖方ID> <金额> <币种>";

    private final EscrowTradeService service;

    public TradeCommandHandler(EscrowTradeService service) {
        if (service == null) {
            throw new TggException("命令处理：未提供交易服务");
        }
        this.service = service;
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

        if (!SUB_CREATE.equals(cmd.argOpt(0).orElse(null))) {
            return USAGE;
        }

        Long sellerId = parseLong(cmd.argOpt(1).orElse(null));
        BigDecimal amount = parseAmount(cmd.argOpt(2).orElse(null));
        String currency = cmd.argOpt(3).orElse(null);
        if (sellerId == null || amount == null || currency == null || currency.isBlank()) {
            return USAGE;
        }

        TradeInitiationResult result;
        try {
            result = service.initiate(
                    new TradeInitiationRequest(actor.userId(), sellerId, amount, currency));
        } catch (EscrowException ex) {
            // 请求自身不合法（自交易、金额非正等）——事实错误，直接告知
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
