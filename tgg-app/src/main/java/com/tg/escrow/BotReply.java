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
 * @param text         回执正文
 * @param offerWebApp  是否适合在该回执上附「打开表单」按钮
 */
public record BotReply(String text, boolean offerWebApp) {

    /** 纯文本回执（不引导表单）。 */
    public static BotReply plain(String text) {
        return new BotReply(text, false);
    }

    /** 引导表单的回执（如"用法"说明——用户正需要知道怎么下单）。 */
    public static BotReply withWebApp(String text) {
        return new BotReply(text, true);
    }
}
