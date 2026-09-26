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
 * 交易状态视图（原文档 T2）：把订单状态转成面向用户的可读摘要与"下一步该谁做什么"。
 *
 * <h2>只讲状态语义，不带主体字段</h2>
 * <p>视图<b>不含</b>买卖双方 ID、金额、币种等订单主体字段。原因：这些文案会被回复到群聊里，
 * 群成员都能看到；把用户 ID 或金额带进通用状态文案，等于在公开场合重复暴露敏感信息。
 * 需要展示具体主体的地方，由调用方另行拼装并施以脱敏。
 *
 * @param state    订单状态
 * @param summary  用户可读的当前状态摘要
 * @param nextStep 下一步该谁做什么（终态为「无」）
 */
public record TradeStatusView(EscrowOrder.State state, String summary, String nextStep) {

    /** 由状态构造视图。{@code state} 为 {@code null} 时 fail-closed。 */
    public static TradeStatusView of(EscrowOrder.State state) {
        if (state == null) {
            throw new EscrowException("交易状态视图：状态不可为空");
        }
        return switch (state) {
            // OPEN 只由命令层两步流产生（买方预览风险 → 确认落单），此后真正可走的下一步是**买方托管资金**
            // （EscrowOrder.markLocked 允许 OPEN→LOCKED）。原文案写「等待卖方确认」/「卖方确认接单」，
            // 指向一个命令层并不存在的节点——会指挥用户去做做不到的事。
            case OPEN -> new TradeStatusView(state, "订单已创建，等待买方托管资金", "买方托管资金");
            case CONFIRMED -> new TradeStatusView(state, "卖方已确认，等待买方托管资金", "买方托管资金");
            case LOCKED -> new TradeStatusView(state, "资金已托管，等待卖方交付", "卖方交付");
            case DELIVERED -> new TradeStatusView(state, "卖方已交付，等待买方验收", "买方确认收货，或发起争议");
            case DISPUTED -> new TradeStatusView(state, "订单争议中，资金暂停结算", "联邦节点裁决");
            case RELEASED -> new TradeStatusView(state, "已放款给卖方，交易完成", "无（交易已完成）");
            case REFUNDED -> new TradeStatusView(state, "已退款给买方，交易结束", "无（交易已结束）");
            case CANCELLED -> new TradeStatusView(state, "订单已取消", "无（订单已取消）");
        };
    }
}
