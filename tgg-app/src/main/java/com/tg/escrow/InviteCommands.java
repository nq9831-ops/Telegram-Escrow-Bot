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
import com.tg.escrow.core.BotCommand;
import com.tg.escrow.core.CommandActor;
import com.tg.escrow.escrow.EscrowGroupGuide;
import com.tg.escrow.escrow.EscrowOrderLookupPort;
import com.tg.escrow.escrow.TimeFormat;
import com.tg.escrow.escrow.TradeAdmissionDecision;
import com.tg.escrow.escrow.TradeInvite;
import com.tg.escrow.escrow.TradeInviteCreationResult;
import com.tg.escrow.escrow.TradeInviteService;

import java.math.BigDecimal;

/**
 * 邀请/我的订单/引导域命令处理器——从 {@link TradeCommandHandler} 拆出（2026-10-03 拆分时方法体逐字保留）。
 *
 * <p>覆盖子命令：{@code invite} / {@code my} / {@code guide}。
 */
final class InviteCommands {

    private static final int MY_ORDERS_LIMIT = 10;

    private final TradeInviteService inviteService;
    private final InviteLink inviteLink;
    private final EscrowOrderLookupPort lookup;

    InviteCommands(TradeInviteService inviteService, InviteLink inviteLink,
                   EscrowOrderLookupPort lookup) {
        this.inviteService = inviteService;
        this.inviteLink = inviteLink;
        this.lookup = lookup;
    }

    /**
     * 深链邀请：建一条待接受邀请，回执带可转发给对方的直链。
     *
     * <p>被拒是<b>正常业务结果</b>（回原因与可重试时刻）；参数非法（外币种/非正金额/缺参）回用法。
     * 建邀请阶段<b>不物化订单</b>——订单只会在对方点开链接接单时产生。
     */
    String handleInvite(BotCommand cmd, CommandActor actor) {
        BigDecimal amount = CommandParsing.parseAmount(cmd.argOpt(1).orElse(null));
        String currency = cmd.argOpt(2).orElse(null);
        if (amount == null || currency == null || currency.isBlank()) {
            return TradeCommandHandler.quickUsage("invite", "<金额> <币种>");
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
            return "被拒：" + CommandParsing.reasonText(decision.reason()) + "，可重试：" + retry;
        }

        TradeInvite invite = result.invite();
        return "已创建待接受邀请（" + MoneyFormat.format(invite.getAmount()) + " " + invite.getCurrency()
                + "，有效期至 " + TimeFormat.shortUtc(invite.getExpiresAt()) + "）\n"
                + "把下面链接发给对方，对方点开即自动接单：\n"
                + inviteLink.forToken(invite.getToken());
    }

    /**
     * 「我的订单」：列出自己最近参与的订单——<b>不必记订单号</b>。
     *
     * <p>存在的理由：其余命令都要先知道订单号，聊天记录一删（或在群里被刷走）就再也找不回
     * 自己的单。只列<b>自己参与</b>的单（买卖任一侧）；不提供按他人 ID 列举的入口——
     * 与 {@code /escrow credit} 的「只查自己」同一隐私取向。渲染规则在 {@link MyOrdersView}。
     */
    String handleMy(CommandActor actor) {
        return MyOrdersView.render(lookup.recentFor(actor.userId(), MY_ORDERS_LIMIT), actor.userId());
    }

    String handleGuide() {
        StringBuilder sb = new StringBuilder("📋 交易群创建引导：");
        EscrowGroupGuide guide = new EscrowGroupGuide();
        EscrowGroupGuide.Step step = EscrowGroupGuide.Step.CREATE_GROUP;
        int n = 1;
        while (step != null) {
            sb.append("\n").append(n++).append("️⃣ ").append(guide.promptFor(step));
            step = guide.next(step).orElse(null);
        }
        return sb.toString();
    }
}
