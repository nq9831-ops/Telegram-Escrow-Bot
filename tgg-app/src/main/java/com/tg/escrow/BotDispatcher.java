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
import com.tg.escrow.core.BannedWordMatcher;
import com.tg.escrow.core.BannedWordRegistry;
import com.tg.escrow.core.CommandActor;
import com.tg.escrow.core.CommandParser;

import java.util.Optional;

/**
 * Bot 命令分发器（S1 路由 + GM-06 违禁词接线）：文本 → 解析 → 违禁词 → 路由 → 回执。
 *
 * <h2>路由语义</h2>
 * <ul>
 *   <li>命令：{@code /escrow …} → {@link TradeCommandHandler}；其它命令 → 帮助回执；
 *       发给别的 bot 的 {@code @} 后缀命令不响应；</li>
 *   <li><b>非命令消息过违禁词</b>（{@link BannedWordRegistry}，按群）——命中给出处置回执
 *       （删消息属 Telegram API 能力，线上接，见 {@code docs/ONLINE-VERIFICATION.md} A5）；
 *       不命中不响应（返回 {@code null}）。</li>
 * </ul>
 *
 * <p>纯逻辑：Telegram Update 解析与 API 调用在 {@code TelegramBotHandler} 胶水层。
 */
public final class BotDispatcher {

    private static final String HELP = "我是担保交易助手。用法：/escrow create <卖方ID> <金额> <币种>——"
            + "创建一笔担保交易。";

    private final TradeCommandHandler tradeHandler;
    private final String botUsername;
    private final BannedWordRegistry bannedWords;

    /**
     * @param tradeHandler 交易命令处理器
     * @param botUsername  本 bot 用户名（不含 {@code @}）
     * @param bannedWords  违禁词注册表（非命令消息的生产消费者）
     */
    public BotDispatcher(TradeCommandHandler tradeHandler, String botUsername,
                         BannedWordRegistry bannedWords) {
        if (tradeHandler == null || bannedWords == null) {
            throw new TggException("命令分发：交易处理器与违禁词注册表均不可为空");
        }
        this.tradeHandler = tradeHandler;
        this.botUsername = botUsername;
        this.bannedWords = bannedWords;
    }

    /**
     * 处理一条消息文本。
     *
     * @param chatId 会话/群 ID（违禁词按群取词库）
     * @param text   消息文本
     * @param actor  发起人
     * @return 回执文本；{@code null} 表示<b>不响应</b>
     */
    public String handle(long chatId, String text, CommandActor actor) {
        if (text == null || actor == null) {
            return null;
        }
        Optional<com.tg.escrow.core.BotCommand> parsed = CommandParser.parse(text, botUsername);
        if (parsed.isPresent()) {
            com.tg.escrow.core.BotCommand cmd = parsed.get();
            if (tradeHandler.canHandle(cmd)) {
                return tradeHandler.handle(cmd, actor);
            }
            return HELP;
        }
        // 非命令消息：违禁词检查（命中给出处置回执）
        Optional<BannedWordMatcher.Match> hit = bannedWords.firstMatch(chatId, text);
        return hit.map(m -> "⚠️ 消息含违禁内容（命中规则：" + m.rule() + "），请文明交流。").orElse(null);
    }
}
