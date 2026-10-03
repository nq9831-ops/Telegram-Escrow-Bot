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
 * 「我的订单」列表渲染（`/escrow my` 的展示层，ET-39 之外的可用性补口）。
 *
 * <h2>它存在的理由</h2>
 * <p>命令表里 12 条命令<b>全都要先知道订单号</b>。用户的聊天记录一删（或在群里被刷走），
 * 就再也找回不了自己的单——本列表是那个缺口的解药。因此钉住三件事：
 * <ul>
 *   <li>空列表给<b>可读文案</b>（不是空白），并指路下一步；</li>
 *   <li>每单一行，含<b>订单号</b>（找回的核心）与状态摘要（复用 {@code TradeStatusView} 的单点文案）；</li>
 *   <li><b>对手方 ID 必须脱敏</b>——回执可能发在群聊里，与 {@code RankPrivacy} 的榜单脱敏同一取向。</li>
 * </ul>
 */
class MyOrdersViewTest {

    private static final long VIEWER = 1001L;

    @Test
    @DisplayName("空列表 → 可读文案（不是空白），并指路 /escrow guide")
    void emptyListSaysSomethingReadable() {
        String out = MyOrdersView.render(List.of(), VIEWER);

        assertThat(out).doesNotContain("#");
        assertThat(out).contains("没有").contains("/escrow guide");
    }

    @Test
    @DisplayName("多条 → 每单一行，含订单号、金额币种与状态摘要")
    void rendersOneLinePerOrder() {
        EscrowOrder o1 = order(3L, VIEWER, 2002L, "100", "USDT");
        EscrowOrder o2 = order(2L, 2002L, VIEWER, "50", "TON");

        String out = MyOrdersView.render(List.of(o1, o2), VIEWER);

        assertThat(out).contains("#3").contains("#2");
        assertThat(out).contains("100 USDT").contains("50 TON");
    }

    @Test
    @DisplayName("对手方 ID 必须脱敏（回执可能进群）——不得出现完整对手 ID")
    void counterpartyIdIsMasked() {
        EscrowOrder o = order(3L, VIEWER, 2002L, "100", "USDT");

        String out = MyOrdersView.render(List.of(o), VIEWER);

        assertThat(out).as("对手 2002 不得以明文出现").doesNotContain("2002");
        assertThat(out).contains("2***2");
    }

    @Test
    @DisplayName("作为卖方时，对手方显示的是买方 ID（同样脱敏）")
    void showsCounterpartyForSellerSideToo() {
        EscrowOrder o = order(3L, 3003L, VIEWER, "100", "USDT");

        String out = MyOrdersView.render(List.of(o), VIEWER);

        assertThat(out).contains("3***3");
    }

    @Test
    @DisplayName("金额去尾零：decimal(24,8) 的原样值 100.00000000 应显示成 100")
    void amountIsStrippedOfTrailingZeros() {
        EscrowOrder o = order(3L, VIEWER, 2002L, "100.00000000", "USDT");

        String out = MyOrdersView.render(List.of(o), VIEWER);

        assertThat(out).as("列表与费用回执同源——不该一个去尾零、一个不去")
                .contains("100 USDT").doesNotContain("100.00000000");
    }

    /** 造一笔订单（状态恒从 OPEN 起步）并回填主键——列表渲染只读它的字段。 */
    private static EscrowOrder order(long id, long buyer, long seller, String amount, String currency) {
        EscrowOrder o = new EscrowOrder(buyer, seller, new BigDecimal(amount), currency,
                Instant.parse("2026-09-30T12:00:00Z"));
        o.assignId(id);
        return o;
    }
}
