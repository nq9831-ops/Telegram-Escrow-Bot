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

import java.util.List;

/**
 * 订单卡片的动作按钮矩阵（状态 × 角色）——纯函数。
 *
 * <h2>按钮是提示，不是授权</h2>
 * <p>谁点了按钮，动作仍要过服务层的守卫（仅买方 / 仅卖方 / 状态）。但<b>提示错了同样有害</b>：
 * 给卖方显示「验收放款」会让他以为点得动，点下去得到一句拒绝，比不给按钮更糟。故本类按
 * 「此刻谁真能做这件事」生成，逐格钉在 {@code ActionButtonsTest} 里。
 *
 * <h2>两条刻意的"不给按钮"</h2>
 * <ul>
 *   <li>需要<b>文本参数</b>的动作（{@code /escrow dispute} 要理由、{@code /escrow review} 要评分）——
 *       纯按钮点了只会看到用法提示，不如不做；</li>
 *   <li>{@code DISPUTED} 之外没有"双方都能做"的情形，故只有争议态下双方看到同一组按钮。</li>
 * </ul>
 */
public final class ActionButtons {

    private ActionButtons() {
    }

    /**
     * 某订单对某查看者可见的动作按钮。
     *
     * @param order    订单（{@code id} 为空 = 尚未落库）
     * @param viewerId 查看者
     * @return 按当前状态与该角色可执行的动作；无从执行时为空列表（<b>不是</b> null）
     */
    public static List<BotReply.ActionButton> forOrder(EscrowOrder order, long viewerId) {
        if (order == null || order.getId() == null) {
            // 未落库 = 没有可指向的订单号，按钮无从生成（且 encode 会拒绝非正 id）
            return List.of();
        }
        boolean buyer = order.getBuyerUserId() == viewerId;
        boolean seller = order.getSellerUserId() == viewerId;
        if (!buyer && !seller) {
            return List.of();
        }
        long id = order.getId();
        return switch (order.currentState()) {
            case OPEN, CONFIRMED -> buyer ? List.of(lock(id)) : List.of();
            case LOCKED -> seller ? List.of(deliver(id)) : List.of();
            case DELIVERED -> buyer ? List.of(release(id), refund(id)) : List.of();
            // 争议态：协商放款 / 协商退款双方都能提（与服务层守卫一致）
            case DISPUTED -> List.of(release(id), refund(id));
            case RELEASED, REFUNDED, CANCELLED -> List.of();
        };
    }

    /**
     * 「查看该单」——列表项用。点开即为该单的状态卡，那里才有可执行动作
     * （列表上直接挂动作太密，也更容易误点）。
     */
    public static BotReply.ActionButton viewButton(long orderId) {
        return new BotReply.ActionButton("🔎 #" + orderId, ActionCodec.encode("st", orderId));
    }

    /**
     * 「确认执行」——资金动作（release / refund）二次确认的第 2 击入口。
     *
     * <p>第一次点击（{@code rl:3}）与命令层同样只<b>发起</b>、不执行；本按钮携带三段确认形态
     * （{@code rl:3:y}），点击后解码为 {@code /escrow release 3 --yes}——与用户在文本里
     * 补发 {@code --yes} 严格等价。文案自带「确认」与订单号，降低误点。
     *
     * @param orderId 订单号
     * @param verb    动作缩写（{@code rl} / {@code rf}）——与 {@link ActionCodec#encode} 同口径
     */
    public static BotReply.ActionButton confirmButton(long orderId, String verb) {
        return new BotReply.ActionButton("⚠️ 确认" + confirmLabel(verb) + " #" + orderId,
                ActionCodec.encode(verb, orderId, true));
    }

    private static String confirmLabel(String verb) {
        return switch (verb) {
            case "rl" -> "放款";
            case "rf" -> "退款";
            default -> "执行";
        };
    }

    private static BotReply.ActionButton lock(long orderId) {
        return new BotReply.ActionButton("💳 托管 #" + orderId, ActionCodec.encode("lk", orderId));
    }

    private static BotReply.ActionButton deliver(long orderId) {
        return new BotReply.ActionButton("📦 交付 #" + orderId, ActionCodec.encode("dl", orderId));
    }

    private static BotReply.ActionButton release(long orderId) {
        return new BotReply.ActionButton("✅ 验收放款 #" + orderId, ActionCodec.encode("rl", orderId));
    }

    private static BotReply.ActionButton refund(long orderId) {
        return new BotReply.ActionButton("💸 退款 #" + orderId, ActionCodec.encode("rf", orderId));
    }
}
