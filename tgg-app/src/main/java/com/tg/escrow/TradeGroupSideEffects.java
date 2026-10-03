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

import com.tg.escrow.core.TradeGroupException;
import com.tg.escrow.core.TradeGroupPort;
import com.tg.escrow.escrow.EscrowOrder;
import com.tg.escrow.escrow.TradeGroupService;

/**
 * 交易群联动（ET-05/06/39/40）——从 {@link TradeCommandHandler} 拆出
 * （2026-10-03 二轮拆分，方法体逐字保留）。
 *
 * <p>四件事：群内落单绑定+公告置顶、订单终态进静默期、静默期内再争议拉回留痕、
 * 静默期满惰性归档。共同纪律：<b>群侧失败绝不回滚交易</b>——回执如实说「未生效」，
 * 绝不假装已建群/已归档（Bot API 无 bot 建群能力，群由用户创建后拉 bot 入群）。
 */
final class TradeGroupSideEffects {

    private final TradeGroupService tradeGroupService;
    private final TradeGroupPort tradeGroupPort;

    TradeGroupSideEffects(TradeGroupService tradeGroupService, TradeGroupPort tradeGroupPort) {
        this.tradeGroupService = tradeGroupService;
        this.tradeGroupPort = tradeGroupPort;
    }

    /**
     * 群内落单 → 绑定本群为交易群并置顶规则公告（ET-05/06）。群侧失败不回滚交易——
     * 回执如实说「群未绑定/群动作未生效」，绝不假装已建群。
     */
    String onTradeCreatedInGroup(EscrowOrder order, long chatId) {
        if (chatId >= 0) {
            // 私聊/无群上下文落单：交易群是可选载体，不噪声提示——想知道怎么建群看 /escrow guide
            return "";
        }
        try {
            tradeGroupService.onTradeCreated(order, chatId);
            int messageId = tradeGroupPort.announce(chatId,
                    "📢 本群已绑定为订单 #" + order.getId() + " 的交易群，全程留痕。"
                            + "如有争议请发起 /escrow dispute " + order.getId());
            tradeGroupPort.pin(chatId, messageId);
            return "\n交易群：已绑定本群，规则公告已置顶";
        } catch (TradeGroupException ex) {
            return "\n交易群未绑定/群动作未生效：" + ex.getMessage();
        }
    }

    /** 订单终态（放款/退款/取消）→ 交易群进静默期并公告（ET-40）；群侧失败不回滚交易。 */
    String settleGroupIfNeeded(EscrowOrder order, TradeEvent event) {
        if (event != TradeEvent.RELEASED && event != TradeEvent.REFUNDED && event != TradeEvent.CANCELLED) {
            return "";
        }
        try {
            return tradeGroupService.onTradeSettled(order)
                    .map(g -> {
                        tradeGroupPort.announce(g.getChatId(),
                                "📢 交易 #" + order.getId() + " 已结算。本群进入静默期，期满自动归档；"
                                        + "期内如有新争议请发起 /escrow dispute " + order.getId());
                        return "\n交易群：已进入静默期（期满自动归档）";
                    })
                    .orElse("");
        } catch (TradeGroupException ex) {
            return "\n交易群动作未生效：" + ex.getMessage();
        }
    }

    /** 静默期内再争议 → 群侧拉回留痕（disputeOpened）；无绑定/未静默时空操作。 */
    String disputeInSilenceGroup(long orderId) {
        try {
            return tradeGroupService.onDisputeInSilence(orderId)
                    .map(g -> {
                        tradeGroupPort.announce(g.getChatId(),
                                "📢 交易 #" + orderId + " 出现新争议，本群恢复留痕，静默期终止。");
                        return "\n交易群：本群留痕已延长（静默期争议）";
                    })
                    .orElse("");
        } catch (TradeGroupException ex) {
            return "\n交易群动作未生效：" + ex.getMessage();
        }
    }

    /**
     * 群内下一次交互时的惰性归档结算（ET-39，不引入调度器）——
     * 群静默期满后有消息到达即公告归档并退群；到期未交互的群保持现状直到有人说话。
     *
     * <p><b>归档成立时不产生任何「群内回执」</b>：公告已发到群里（成员看得到），随即
     * {@code tradeGroupPort.leave} 退群——再往该群发文本必然 403。故本方法只回报「已归档」
     * 这一个事实，由调用方据此跳过本条消息的处理。
     *
     * @return {@code true} = 本次完成归档（公告已发、bot 已退群；调用方应跳过该消息处理）；
     *         {@code false} = 无动作（无绑定 / 未到期 / 非静默态 / 群动作失败）
     */
    boolean reapIfDue(long chatId) {
        try {
            return tradeGroupService.onGroupMessage(chatId)
                    .filter(g -> g.currentState() == com.tg.escrow.escrow.TradeGroupLifecycle.State.ARCHIVED)
                    .map(g -> {
                        tradeGroupPort.announce(chatId, "📢 交易 #" + g.getTradeId()
                                + " 的静默期已满，本群归档。bot 将退出本群，如需继续请新建交易。");
                        tradeGroupPort.leave(chatId);
                        return true;
                    })
                    .orElse(false);
        } catch (TradeGroupException ex) {
            return false;
        }
    }
}
