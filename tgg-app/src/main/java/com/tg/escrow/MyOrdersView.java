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

import com.tg.escrow.escrow.EscrowOrder;
import com.tg.escrow.escrow.RankPrivacy;
import com.tg.escrow.escrow.TradeStatusView;

import java.util.List;

/**
 * 「我的订单」列表渲染（`/escrow my` 的展示层）。
 *
 * <h2>为什么需要这个出口</h2>
 * <p>命令表里 12 条命令<b>全都要先知道订单号</b>。用户的聊天记录一删（或在群里被消息刷走），
 * 就再也找不回自己的单——本列表是那个缺口唯一的解药，因此<b>订单号必须在每一行上</b>。
 *
 * <h2>隐私取向（与 {@code TradeStatusView} 同源）</h2>
 * <p>回执可能出现在<b>群聊</b>里，所以：
 * <ul>
 *   <li><b>对手方 ID 一律脱敏</b>（复用 {@link RankPrivacy#display}——首字符 + {@code ***} + 末字符，
 *       本人认得出、他人反查不到）；</li>
 *   <li>只列<b>自己参与</b>的单（买卖任一侧），不提供按他人 ID 列举的形态。</li>
 * </ul>
 *
 * <p>状态文案复用 {@link TradeStatusView}（域层单点），本类不另造一套措辞。
 */
public final class MyOrdersView {

    /** 无成交史时的回执——不是空白，并给出下一步可执行的入口。 */
    private static final String EMPTY =
            "你还没有参与过任何交易。发 /escrow guide 了解如何发起一笔担保交易。";

    private MyOrdersView() {
    }

    /**
     * 渲染列表。
     *
     * @param orders   要展示的订单（调用方已按时间倒序排好；本类不重排）
     * @param viewerId 查看者 ID——用于判定"对手方是哪一侧"
     * @return 可直接发送的文本；空列表时返回 {@link #EMPTY}
     */
    public static String render(List<EscrowOrder> orders, long viewerId) {
        if (orders == null || orders.isEmpty()) {
            return EMPTY;
        }
        StringBuilder sb = new StringBuilder("📋 你的订单（最近 " + orders.size() + " 笔）");
        for (EscrowOrder order : orders) {
            sb.append("\n#").append(order.getId())
                    .append(" · ").append(TradeStatusView.of(order.currentState()).summary())
                    .append(" · ").append(MoneyFormat.format(order.getAmount()))
                    .append(' ').append(order.getCurrency())
                    .append(" · 对手 ").append(RankPrivacy.display(
                            String.valueOf(counterpartyOf(order, viewerId)), false));
        }
        return sb.toString();
    }

    /** 对手方 = 查看者不是的那一侧。 */
    private static long counterpartyOf(EscrowOrder order, long viewerId) {
        return order.getBuyerUserId() == viewerId ? order.getSellerUserId() : order.getBuyerUserId();
    }
}
