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

import java.util.List;

/**
 * 回复出口——把回执交回 Telegram 的最小契约。语义要点：
 * ①策略重载只影响响铃（disable_notification），不影响送达；②web_app 按钮 url 必须 HTTPS；
 * ③动作按钮不是授权——callback 处理方仍走与文本命令相同的守卫链；④ackCallback 必须调用
 * （不调 = 客户端按钮一直转圈，正确性问题）。
 */
public interface BotReplyPort {

    /** 发送文本回执。 */
    void sendText(long chatId, String text);

    /** 发送文本回执并声明通知策略（policy 不可为空）。 */
    void sendText(long chatId, String text, NoticePolicy policy);

    /** 发送带「打开表单」按钮的回执（url 必须 HTTPS 且与 bot 关联域名一致）。 */
    void sendTextWithWebApp(long chatId, String text, String buttonText, String url);

    /** 发送带动作按钮的回执（buttons 不可为空）。 */
    void sendTextWithButtons(long chatId, String text, List<BotReply.ActionButton> buttons);

    /** 带按钮 + 通知策略（通知路径专用：静默语义与按钮缺一不可）。 */
    void sendTextWithButtons(long chatId, String text, List<BotReply.ActionButton> buttons,
                             NoticePolicy policy);

    /** 应答 callback query（必须调用）。 */
    void ackCallback(String callbackQueryId);
}
