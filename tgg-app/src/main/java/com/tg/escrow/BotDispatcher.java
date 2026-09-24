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
import com.tg.escrow.core.CommandActor;
import com.tg.escrow.core.CommandParser;

import java.util.Optional;

/**
 * Bot 命令分发器（S1）：文本 → 解析 → 路由 → 回执。
 *
 * <h2>路由语义</h2>
 * <ul>
 *   <li><b>非命令 → 不响应（{@code null}）</b>：群里绝大多数消息与 Bot 无关；</li>
 *   <li>{@code /escrow …} → {@link TradeCommandHandler}（交易命令）；</li>
 *   <li>其它已解析命令 → 帮助回执（含 escrow 用法）；</li>
 *   <li>带 {@code @} 后缀但发给别的 bot → 不响应（多 bot 共存不打架，见 {@code CommandParser}）。</li>
 * </ul>
 *
 * <p>纯逻辑：Telegram Update 的解析与 API 调用在 {@code TelegramBotHandler} 胶水层。
 */
public final class BotDispatcher {

    private static final String HELP = "我是担保交易助手。用法：/escrow create <卖方ID> <金额> <币种>——"
            + "创建一笔担保交易。";

    private final TradeCommandHandler tradeHandler;
    private final String botUsername;

    /**
     * @param tradeHandler 交易命令处理器
     * @param botUsername  本 bot 用户名（不含 {@code @}；用于 {@code @} 后缀归属判定）
     */
    public BotDispatcher(TradeCommandHandler tradeHandler, String botUsername) {
        if (tradeHandler == null) {
            throw new TggException("命令分发：交易处理器未提供");
        }
        this.tradeHandler = tradeHandler;
        this.botUsername = botUsername;
    }

    /**
     * 处理一条消息文本。
     *
     * @param text   消息文本
     * @param actor  发起人（角色由上游确认；暂缺时调用方给最低角色）
     * @return 回执文本；{@code null} 表示<b>不响应</b>
     */
    public String handle(String text, CommandActor actor) {
        if (text == null || actor == null) {
            return null;
        }
        java.util.Optional<com.tg.escrow.core.BotCommand> parsed = CommandParser.parse(text, botUsername);
        if (parsed.isEmpty()) {
            return null;
        }
        com.tg.escrow.core.BotCommand cmd = parsed.get();
        if (tradeHandler.canHandle(cmd)) {
            return tradeHandler.handle(cmd, actor);
        }
        return HELP;
    }
}
