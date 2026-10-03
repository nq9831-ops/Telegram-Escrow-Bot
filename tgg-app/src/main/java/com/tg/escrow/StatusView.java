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

import com.tg.escrow.core.BotCommand;
import com.tg.escrow.escrow.EscrowOrder;
import com.tg.escrow.escrow.EscrowOrderLookupPort;
import com.tg.escrow.escrow.TradeMaintenanceService;
import com.tg.escrow.escrow.TradeStatusView;

import java.time.Duration;

/**
 * 订单状态查询渲染——从 {@link TradeCommandHandler} 拆出（2026-10-03 二轮拆分，方法体逐字保留）。
 *
 * <p>查不到<b>不是错误</b>——按用户视角回「不存在」。视图文案本身不含买卖双方 ID 与金额
 * （{@link TradeStatusView} 的既定约束），可安全回在群聊里。命令语法刻意留在命令层：
 * 域层只说状态与人话，不携带 Bot 的命令词汇。
 */
final class StatusView {

    private final EscrowOrderLookupPort lookup;
    private final TradeMaintenanceService maintenanceService;

    StatusView(EscrowOrderLookupPort lookup, TradeMaintenanceService maintenanceService) {
        this.lookup = lookup;
        this.maintenanceService = maintenanceService;
    }

    String handleStatus(BotCommand cmd) {
        Long orderId = CommandParsing.parseLong(cmd.argOpt(1).orElse(null));
        if (orderId == null) {
            return TradeCommandHandler.quickUsage("status", "<订单号>");
        }
        EscrowOrder order = lookup.byId(orderId).orElse(null);
        if (order == null) {
            return "订单 #" + orderId + " 不存在（请核对订单号）";
        }
        TradeStatusView view = TradeStatusView.of(order.currentState());
        return "订单 #" + orderId + "：" + view.summary()
                + "\n下一步：" + view.nextStep()
                + statusHint(orderId, view.state())
                + maintenanceNote(order)
                + fundCaveat(view.state());
    }

    /**
     * 交付后的维护期提示：<b>只报告事实</b>（还剩多久 / 已过），并明说不自动执行。
     */
    private String maintenanceNote(EscrowOrder order) {
        TradeMaintenanceService.MaintenanceStatus status = maintenanceService.statusOf(order);
        if (!status.applicable()) {
            return "";
        }
        if (status.expired()) {
            return "\n维护期已过，可验收放款（注：无调度器，不会自动执行）。";
        }
        return "\n维护期剩余 " + humanDuration(status.remaining())
                + "，期满后可验收放款（注：无调度器，不会自动执行）。";
    }

    /** 把时长说成人能读的话——秒级对用户无意义，最小到分钟。 */
    private static String humanDuration(Duration d) {
        long hours = d.toHours();
        long minutes = d.toMinutesPart();
        if (hours > 0) {
            return hours + " 小时" + (minutes > 0 ? " " + minutes + " 分钟" : "");
        }
        return Math.max(minutes, 1) + " 分钟";
    }

    /**
     * 给出「此刻真实可用」的命令——否则状态文案会指挥用户去发一条不存在的命令。
     */
    private static String statusHint(long orderId, EscrowOrder.State state) {
        return switch (state) {
            case OPEN -> "\n可用操作：/escrow lock " + orderId + "（买方托管登记）";
            case CONFIRMED -> "\n可用操作：/escrow lock " + orderId + "（买方托管登记）";
            case LOCKED -> "\n可用操作：/escrow deliver " + orderId + "（卖方交付）、"
                    + "/escrow dispute " + orderId + " <理由>（发起争议）";
            case DELIVERED -> "\n可用操作：/escrow release " + orderId + "（买方验收放款）、"
                    + "/escrow dispute " + orderId + " <理由>（发起争议）";
            case DISPUTED -> "\n可用操作：/escrow release " + orderId + "（协商放款）、"
                    + "/escrow refund " + orderId + " <理由>（协商退款）";
            case RELEASED, REFUNDED -> "\n可用操作：/escrow review " + orderId + " <评分1-5>（评价本单）";
            case CANCELLED -> "";
        };
    }

    /** 状态语义涉及资金时，回执必须带上链上未接入的标注——否则等于默认「钱动了」。 */
    private static String fundCaveat(EscrowOrder.State state) {
        return switch (state) {
            case LOCKED, RELEASED, REFUNDED -> "\n" + TradeCommandHandler.CHAIN_CAVEAT;
            default -> "";
        };
    }
}
