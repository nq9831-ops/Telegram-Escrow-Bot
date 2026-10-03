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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 订单卡片的动作按钮矩阵（状态 × 角色）。
 *
 * <h2>它钉的是什么</h2>
 * <p>按钮是"当前角色此刻真能做的事"的<b>提示</b>——不是授权（授权在服务层）。但提示错了同样有害：
 * 给卖方显示「验收放款」会让他以为点得动，点下去得到一句拒绝，比没有按钮更糟。故本表逐格钉死。
 *
 * <p>两条刻意的<b>不给按钮</b>：
 * <ul>
 *   <li>需要文本参数的动作（`/escrow dispute` 要理由、`/escrow review` 要评分）——
 *       纯按钮点了只会看到用法提示，不如不做；</li>
 *   <li>终态订单——没有可推进的动作。</li>
 * </ul>
 */
class ActionButtonsTest {

    private static final long BUYER = 1001L;
    private static final long SELLER = 2002L;
    private static final long STRANGER = 9999L;

    @Test
    @DisplayName("OPEN / CONFIRMED：只有买方见「托管」——待托管是买方的事")
    void openAndConfirmedOfferLockToBuyerOnly() {
        for (EscrowOrder order : List.of(orderAt(EscrowOrder.State.OPEN), orderAt(EscrowOrder.State.CONFIRMED))) {
            assertThat(callbacks(ActionButtons.forOrder(order, BUYER)))
                    .containsExactly("lk:7");
            assertThat(ActionButtons.forOrder(order, SELLER))
                    .as("托管是买方动作——给卖方按钮等于给他一个点不动的按钮").isEmpty();
        }
    }

    @Test
    @DisplayName("LOCKED：只有卖方见「交付」")
    void lockedOffersDeliverToSellerOnly() {
        EscrowOrder order = orderAt(EscrowOrder.State.LOCKED);

        assertThat(callbacks(ActionButtons.forOrder(order, SELLER))).containsExactly("dl:7");
        assertThat(ActionButtons.forOrder(order, BUYER)).isEmpty();
    }

    @Test
    @DisplayName("DELIVERED：买方见「验收放款」与「退款」")
    void deliveredOffersReleaseAndRefundToBuyer() {
        EscrowOrder order = orderAt(EscrowOrder.State.DELIVERED);

        assertThat(callbacks(ActionButtons.forOrder(order, BUYER)))
                .containsExactly("rl:7", "rf:7");
        assertThat(ActionButtons.forOrder(order, SELLER)).isEmpty();
    }

    @Test
    @DisplayName("DISPUTED：协商放款/退款双方都能提，故双方都见两个按钮")
    void disputedOffersNegotiationToBothParties() {
        EscrowOrder order = orderAt(EscrowOrder.State.DISPUTED);

        assertThat(callbacks(ActionButtons.forOrder(order, BUYER))).containsExactly("rl:7", "rf:7");
        assertThat(callbacks(ActionButtons.forOrder(order, SELLER))).containsExactly("rl:7", "rf:7");
    }

    @Test
    @DisplayName("终态（RELEASED / REFUNDED / CANCELLED）：无按钮——没有可推进的动作")
    void terminalStatesOfferNothing() {
        for (EscrowOrder.State state : List.of(EscrowOrder.State.RELEASED,
                EscrowOrder.State.REFUNDED, EscrowOrder.State.CANCELLED)) {
            assertThat(ActionButtons.forOrder(orderAt(state), BUYER)).isEmpty();
            assertThat(ActionButtons.forOrder(orderAt(state), SELLER)).isEmpty();
        }
    }

    @Test
    @DisplayName("旁观者：一个按钮都不给（即便点了也会被守卫拒——但不必先给他点得动的假象）")
    void strangerSeesNothing() {
        assertThat(ActionButtons.forOrder(orderAt(EscrowOrder.State.LOCKED), STRANGER)).isEmpty();
    }

    @Test
    @DisplayName("未落库的订单（id 为空）：不生成按钮，也不抛")
    void unpersistedOrderYieldsNothing() {
        EscrowOrder order = new EscrowOrder(BUYER, SELLER, new BigDecimal("100"), "USDT",
                Instant.parse("2026-09-30T12:00:00Z"));

        assertThat(ActionButtons.forOrder(order, BUYER)).isEmpty();
    }

    @Test
    @DisplayName("viewButton：列表项按钮的 callbackData 是 st:<id>（点开即该单的状态卡）")
    void viewButtonCarriesStatusCallback() {
        assertThat(ActionButtons.viewButton(7).callbackData()).isEqualTo("st:7");
        assertThat(ActionButtons.viewButton(7).text()).contains("#7");
    }

    private static List<String> callbacks(List<BotReply.ActionButton> buttons) {
        return buttons.stream().map(BotReply.ActionButton::callbackData).toList();
    }

    private static EscrowOrder orderAt(EscrowOrder.State state) {
        EscrowOrder order = new EscrowOrder(BUYER, SELLER, new BigDecimal("100"), "USDT",
                Instant.parse("2026-09-30T12:00:00Z"));
        order.assignId(7L);
        // 状态推进走真实迁移链，不用反射造非法态
        switch (state) {
            case OPEN -> { }
            case CONFIRMED -> order.markConfirmed(Instant.parse("2026-09-30T12:01:00Z"));
            case LOCKED -> {
                order.markConfirmed(Instant.parse("2026-09-30T12:01:00Z"));
                order.markLocked(Instant.parse("2026-09-30T12:02:00Z"));
            }
            case DELIVERED -> {
                order.markConfirmed(Instant.parse("2026-09-30T12:01:00Z"));
                order.markLocked(Instant.parse("2026-09-30T12:02:00Z"));
                order.markDelivered(Instant.parse("2026-09-30T12:03:00Z"));
            }
            case DISPUTED -> {
                order.markConfirmed(Instant.parse("2026-09-30T12:01:00Z"));
                order.markLocked(Instant.parse("2026-09-30T12:02:00Z"));
                order.markDisputed("货不对板", Instant.parse("2026-09-30T12:03:00Z"));
            }
            case RELEASED -> {
                order.markConfirmed(Instant.parse("2026-09-30T12:01:00Z"));
                order.markLocked(Instant.parse("2026-09-30T12:02:00Z"));
                order.markReleased(Instant.parse("2026-09-30T12:03:00Z"));
            }
            case REFUNDED -> {
                order.markConfirmed(Instant.parse("2026-09-30T12:01:00Z"));
                order.markLocked(Instant.parse("2026-09-30T12:02:00Z"));
                order.markRefunded("协商退款", Instant.parse("2026-09-30T12:03:00Z"));
            }
            case CANCELLED -> order.markCancelled("当事人取消", Instant.parse("2026-09-30T12:01:00Z"));
        }
        return order;
    }
}
