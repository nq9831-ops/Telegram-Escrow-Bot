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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 回执载体 {@link BotReply} 与动作按钮 {@link BotReply.ActionButton} 的值约束。
 *
 * <h2>为什么校验放在值对象里而不是发送处</h2>
 * <p>{@code callback_data} 的 64 字节是 <b>Telegram 的硬上限</b>——超限的按钮发不出去，
 * 而"按钮没了"会被用户理解为"这单没有可做的操作"。把校验放在<b>构造它的那一行</b>，
 * 出错时炸在产生它的地方（fail-fast），而不是等到几十毫秒后的 API 调用。
 */
class BotReplyTest {

    @Test
    @DisplayName("plain / withWebApp 不带任何动作按钮——既有回执行为一字不变")
    void factoriesCarryNoButtons() {
        assertThat(BotReply.plain("x").buttons()).isEmpty();
        assertThat(BotReply.withWebApp("x").buttons()).isEmpty();
    }

    @Test
    @DisplayName("withActions 带上按钮；offerWebApp=false（表单按钮与动作按钮不混用）")
    void withActionsCarriesButtons() {
        BotReply reply = BotReply.withActions("订单 #3 …",
                List.of(new BotReply.ActionButton("✅ 验收放款 #3", "rl:3")));

        assertThat(reply.text()).isEqualTo("订单 #3 …");
        assertThat(reply.offerWebApp()).isFalse();
        assertThat(reply.buttons()).singleElement()
                .satisfies(b -> {
                    assertThat(b.text()).isEqualTo("✅ 验收放款 #3");
                    assertThat(b.callbackData()).isEqualTo("rl:3");
                });
    }

    @Test
    @DisplayName("ActionButton：callbackData 非空且 ≤64 字节（Telegram 硬上限，超限即 fail-fast）")
    void actionButtonValidatesCallbackData() {
        assertThatThrownBy(() -> new BotReply.ActionButton("x", null))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new BotReply.ActionButton("x", " "))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new BotReply.ActionButton("x", "a".repeat(65)))
                .as("超 64 字节必须抛——静默截断会让按钮指向错误的订单")
                .isInstanceOf(RuntimeException.class);

        assertThat(new BotReply.ActionButton("x", "a".repeat(64)).callbackData())
                .as("恰好 64 字节是合法的").hasSize(64);
    }

    @Test
    @DisplayName("ActionButton：按钮文案不可为空（没字的按钮等于不可点）")
    void actionButtonValidatesText() {
        assertThatThrownBy(() -> new BotReply.ActionButton(null, "rl:3"))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new BotReply.ActionButton("  ", "rl:3"))
                .isInstanceOf(RuntimeException.class);
    }
}
