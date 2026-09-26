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

import com.tg.escrow.core.NoticePolicy;
import com.tg.escrow.escrow.EscrowOrder;

/**
 * 需要主动告知交易对手方的事件。
 *
 * <h2>为什么要分档，而不是每个事件绑一个策略</h2>
 * <p>枚举只声明"这个事件属于哪一档"（{@link Kind}），由 {@link TradeNotifier} 把档位映射成
 * {@link NoticePolicy} 并施加静默判定。这样"哪些该响铃、哪些可被静默"是一处可读的语义表，
 * 而不是散落在每个常量上的策略构造——后者一旦要调档就会漏改。
 *
 * <h2>三档的判据</h2>
 * <ul>
 *   <li>{@link Kind#CRITICAL}：资金语义（托管/放款/退款）或争议——<b>一律响铃，静默窗口不得压制</b>。
 *       钱的事在半夜也必须吵醒人。</li>
 *   <li>{@link Kind#ACTION_NEEDED}：等对方行动，但非资金——默认响铃，<b>静默窗口可压制响铃</b>
 *       （不影响送达）。</li>
 *   <li>{@link Kind#INFORMATIONAL}：纯告知——本就静默。</li>
 * </ul>
 *
 * <p><b>踩过的坑</b>：本档位是设计复核阶段补上的。最初把"建单/交付"也归进"静默"，结果
 * 静默窗口失去作用对象（资金节点不许静默、信息性节点本就静默）——接上线也是个死开关。
 */
public enum TradeEvent {

    /** 建单：告知卖方"有交易指向你"。 */
    CREATED(Kind.ACTION_NEEDED),

    /** 接单：告知买方"对方已接单"。 */
    ACCEPTED(Kind.ACTION_NEEDED),

    /** 托管：{@code LOCKED}。 */
    LOCKED(Kind.CRITICAL),

    /** 交付：{@code DELIVERED}，待买方验收。 */
    DELIVERED(Kind.ACTION_NEEDED),

    /** 验收放款：{@code RELEASED}。 */
    RELEASED(Kind.CRITICAL),

    /** 退款：{@code REFUNDED}。 */
    REFUNDED(Kind.CRITICAL),

    /** 争议：{@code DISPUTED}。 */
    DISPUTED(Kind.CRITICAL),

    /** 取消：{@code CANCELLED}（状态机保证资金未动）。 */
    CANCELLED(Kind.INFORMATIONAL);

    /** 事件的响铃档位。 */
    public enum Kind {
        /** 资金/争议——一律响铃。 */
        CRITICAL,
        /** 待对方行动——默认响铃、可被静默窗口压制。 */
        ACTION_NEEDED,
        /** 纯告知——本就静默。 */
        INFORMATIONAL
    }

    private final Kind kind;

    TradeEvent(Kind kind) {
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    /**
     * 本事件的默认通知策略（静默窗口的压制不在此体现，由 {@link TradeNotifier} 施加）。
     *
     * <p>{@code ACTION_NEEDED} 用 {@code (PERMANENT, 响铃, 留存)} 显式构造，而不复用
     * {@link NoticePolicy#critical()}——那是"关键节点"的名字，拿它表达"待行动"会让读者
     * 以为该事件是资金节点。
     */
    public NoticePolicy policy() {
        return switch (kind) {
            case CRITICAL -> NoticePolicy.critical();
            case ACTION_NEEDED ->
                    new NoticePolicy(NoticePolicy.Ephemerality.PERMANENT, false, true);
            case INFORMATIONAL -> NoticePolicy.progress();
        };
    }

    /** 是否允许静默窗口压制其响铃（只有"待行动"档可以——资金档不许）。 */
    public boolean silenceable() {
        return kind == Kind.ACTION_NEEDED;
    }

    /**
     * 渲染发给<b>对方</b>的通知文案。
     *
     * <p>资金类事件（{@code LOCKED}/{@code RELEASED}/{@code REFUNDED}）必须带上
     * {@link TradeCommandHandler#CHAIN_CAVEAT}：对用户暗示"钱动了"而实际只登记了状态，
     * 是会致损的谎报——这条纪律与命令回执一致，复用同一个常量，避免两处措辞漂移。
     */
    public String render(EscrowOrder order) {
        long id = order.getId();
        String caveat = "\n" + TradeCommandHandler.CHAIN_CAVEAT;

        return switch (this) {
            case CREATED -> "有人向你发起担保交易（" + order.getAmount().toPlainString() + " "
                    + order.getCurrency() + "）：订单 #" + id + "。";
            case ACCEPTED -> "订单 #" + id + "：对方已接单，待托管资金（/escrow lock " + id + "）。";
            case LOCKED -> "订单 #" + id + "：资金托管已登记，请交付（/escrow deliver " + id + "）。"
                    + caveat;
            case DELIVERED -> "订单 #" + id + "：对方已标记交付，请在维护期内验收"
                    + "（/escrow release " + id + "）。";
            case RELEASED -> "订单 #" + id + "：买方已验收，放款已登记。" + caveat;
            case REFUNDED -> "订单 #" + id + "：退款已登记。" + caveat;
            case DISPUTED -> "订单 #" + id + "：对方已发起争议，等待裁决。";
            case CANCELLED -> "订单 #" + id + "：订单已取消。";
        };
    }
}
