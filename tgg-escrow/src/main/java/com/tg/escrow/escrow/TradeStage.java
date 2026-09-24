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
package com.tg.escrow.escrow;

import com.tg.escrow.common.EscrowException;

/**
 * 交易阶段（原文档 T38）：把 8 个订单状态归入面向用户的粗粒度阶段。
 *
 * <h2>为什么需要它</h2>
 * <p>{@link EscrowOrder.State} 是<b>机器状态</b>（8 个），用户看的是<b>阶段</b>（少数几步）。
 * 直接把状态名投给用户，会让人以为有 8 个平级节点而看不出流程。阶段是那层"人话"。
 *
 * <h2>映射定死</h2>
 * <pre>
 * OPEN, CONFIRMED   → ORDERING     下单（创建 → 卖方确认）
 * LOCKED            → FULFILLING   履约（资金已托管，待交付）
 * DELIVERED         → CONFIRMING   确认（待买方验收）
 * RELEASED          → MAINTENANCE  维护（放款后进入售后维护期）
 * DISPUTED          → DISPUTED     争议
 * REFUNDED          → SETTLEMENT   结算
 * CANCELLED         → CANCELLED    取消
 * </pre>
 *
 * <p>原文档给的阶段是「下单/履约/确认/维护/争议/结算」六项，未含取消——
 * 但 {@code CANCELLED} 是一个必须落到某处的状态，故单列 {@link #CANCELLED} 阶段，
 * 而非把它硬塞进"结算"（取消没有结算发生）。
 */
public enum TradeStage {

    /** 下单：创建到卖方确认。 */
    ORDERING("下单"),
    /** 履约：资金已托管，等待卖方交付。 */
    FULFILLING("履约"),
    /** 确认：卖方已交付，等待买方验收。 */
    CONFIRMING("确认"),
    /** 维护：放款后进入的售后维护期。 */
    MAINTENANCE("维护"),
    /** 争议：进入裁决流程。 */
    DISPUTED("争议"),
    /** 结算：正常退款终态。 */
    SETTLEMENT("结算"),
    /** 取消：未托管的订单被取消。 */
    CANCELLED("取消");

    private final String label;

    TradeStage(String label) {
        this.label = label;
    }

    /** 面向用户的中文标签。 */
    public String label() {
        return label;
    }

    /** 把订单状态映射到阶段；{@code state} 为 {@code null} 时 fail-closed。 */
    public static TradeStage of(EscrowOrder.State state) {
        if (state == null) {
            throw new EscrowException("交易阶段：状态不可为空");
        }
        return switch (state) {
            case OPEN, CONFIRMED -> ORDERING;
            case LOCKED -> FULFILLING;
            case DELIVERED -> CONFIRMING;
            case RELEASED -> MAINTENANCE;
            case DISPUTED -> DISPUTED;
            case REFUNDED -> SETTLEMENT;
            case CANCELLED -> CANCELLED;
        };
    }
}
