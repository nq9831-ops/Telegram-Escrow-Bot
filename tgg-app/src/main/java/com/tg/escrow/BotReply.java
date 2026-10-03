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

import com.tg.escrow.common.TggException;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 一条回执及其<b>可选的动作入口</b>（S6 / Wave 3）。
 *
 * <h2>为什么需要它</h2>
 * <p>纯文本回执表达不了「这条消息该附带一个打开 Mini App 表单的按钮」。
 * 把这一意图塞进文案字符串再在胶水层反解析，既脆又会在文案改动时静默失效——
 * 所以让命令层显式地把「是否引导用户去表单」这一信息带出来（{@code offerWebApp}），
 * 由胶水层决定用哪种出口发送（{@link BotReplyPort#sendText} 或
 * {@link BotReplyPort#sendTextWithWebApp}）。
 *
 * <p>是否真的带按钮，还取决于胶水层是否配置了表单 URL——没配 URL 时退化为纯文本
 * （{@code offerWebApp} 只是"该处适合引导"，不保证按钮一定出现）。
 *
 * <h2>动作按钮（{@link ActionButton}）</h2>
 * <p>给订单回执挂「当前角色此刻能做的事」的按钮，让用户不必记命令。两条硬约束：
 * <ul>
 *   <li><b>按钮不是授权</b>：粘贴点击的 callback 只是把用户"带到"某条命令，权限、状态守卫
 *       仍在服务层照旧执行（与文本命令走同一条链）；</li>
 *   <li>{@code callbackData} 必须 ≤ <b>64 字节</b>（Telegram 硬上限）——校验放在值对象构造期，
 *       炸在产生它的那一行，而不是等到 API 调用。</li>
 * </ul>
 *
 * @param text        回执正文
 * @param offerWebApp 是否适合在该回执上附「打开表单」按钮
 * @param buttons     动作按钮（可为空列表；与 {@code offerWebApp} 不混用）
 */
public record BotReply(String text, boolean offerWebApp, List<ActionButton> buttons) {

    /**
     * 一个 inline 动作按钮。
     *
     * @param text         按钮文字（应自带动作与订单号，降低误点）
     * @param callbackData 点击后回传给 bot 的短标识（≤64 字节）
     */
    public record ActionButton(String text, String callbackData) {

        /** Telegram 对 {@code callback_data} 的硬上限。 */
        private static final int MAX_CALLBACK_BYTES = 64;

        public ActionButton {
            if (text == null || text.isBlank()) {
                throw new TggException("动作按钮：文案不可为空（没字的按钮等于不可点）");
            }
            if (callbackData == null || callbackData.isBlank()) {
                throw new TggException("动作按钮：callbackData 不可为空");
            }
            int bytes = callbackData.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > MAX_CALLBACK_BYTES) {
                throw new TggException("动作按钮：callbackData 超 Telegram 上限（"
                        + bytes + " > " + MAX_CALLBACK_BYTES + " 字节）");
            }
        }
    }

    public BotReply {
        buttons = buttons == null ? List.of() : List.copyOf(buttons);
    }

    /** 纯文本回执（不引导表单、不带动作按钮）。 */
    public static BotReply plain(String text) {
        return new BotReply(text, false, List.of());
    }

    /** 引导表单的回执（如"用法"说明——用户正需要知道怎么下单）。 */
    public static BotReply withWebApp(String text) {
        return new BotReply(text, true, List.of());
    }

    /**
     * 带动作按钮的回执（如订单卡片：买方看到 [验收][退款]，卖方看到 [交付]）。
     *
     * <p>刻意<b>不</b>与 {@code offerWebApp} 混用：一条消息上同时挂"去表单"和"就地操作"
     * 两种按钮，用户分不清哪个是主路径。
     */
    public static BotReply withActions(String text, List<ActionButton> buttons) {
        return new BotReply(text, false, buttons);
    }
}
