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
import com.tg.escrow.core.KeywordAutoReply;

import java.util.Optional;

/**
 * Bot 命令分发器（S1 路由 + GM-06 违禁词 + GM-17 自动回复接线）。
 *
 * <h2>非命令消息的路由顺序（定死）</h2>
 * <ol>
 *   <li><b>违禁词</b>（{@link BannedWordRegistry}，按群）→ 处置回执——安全优先于应答；</li>
 *   <li><b>自动回复</b>（{@link KeywordAutoReply}）→ 预设回复；</li>
 *   <li>都不命中 → 不响应（{@code null}）。</li>
 * </ol>
 *
 * <p>命令：{@code /escrow …} → {@link TradeCommandHandler}；其它命令 → 帮助；
 * 发给别的 bot 的 {@code @} 后缀命令不响应。纯逻辑，Telegram 胶水在 {@code TelegramBotHandler}。
 */
public final class BotDispatcher {

    private static final String HELP = "我是担保交易助手。用法：/escrow invite <金额> <币种> 生成邀请链接"
            + "（对方点开即接单）；/escrow create <卖方ID> <金额> <币种> 预览风险，"
            + "确认后 /escrow confirm 创建交易；/escrow lock|deliver|release|refund|dispute <订单号> "
            + "推进交易；/escrow status <订单号> 查询状态。";

    private final TradeCommandHandler tradeHandler;
    private final String botUsername;
    private final BannedWordRegistry bannedWords;
    private final KeywordAutoReply autoReply;

    /**
     * @param tradeHandler 交易命令处理器
     * @param botUsername  本 bot 用户名（不含 {@code @}）
     * @param bannedWords  违禁词注册表
     * @param autoReply    自动回复（GM-17 的生产消费者）
     */
    public BotDispatcher(TradeCommandHandler tradeHandler, String botUsername,
                         BannedWordRegistry bannedWords, KeywordAutoReply autoReply) {
        if (tradeHandler == null || bannedWords == null || autoReply == null) {
            throw new TggException("命令分发：处理器/违禁词注册表/自动回复均不可为空");
        }
        this.tradeHandler = tradeHandler;
        this.botUsername = botUsername;
        this.bannedWords = bannedWords;
        this.autoReply = autoReply;
    }

    /**
     * 处理一条消息文本。
     *
     * @param chatId 会话/群 ID（违禁词按群取词库）
     * @param text   消息文本
     * @param actor  发起人
     * @return 回执（正文 + 是否附「打开表单」入口）；{@code null} 表示<b>不响应</b>
     */
    public BotReply handle(long chatId, String text, CommandActor actor) {
        if (text == null || actor == null) {
            return null;
        }
        Optional<com.tg.escrow.core.BotCommand> parsed = CommandParser.parse(text, botUsername);
        if (parsed.isPresent()) {
            com.tg.escrow.core.BotCommand cmd = parsed.get();
            if (tradeHandler.canHandle(cmd)) {
                String reply = tradeHandler.handle(cmd, actor);
                // "/escrow" 无子命令或参数不全 → 用法说明：正是该引导用户去表单的时刻
                return TradeCommandHandler.USAGE.equals(reply)
                        ? BotReply.withWebApp(reply)
                        : BotReply.plain(reply);
            }
            return BotReply.withWebApp(HELP);
        }
        // 1) 违禁词优先（安全 > 应答）
        Optional<BannedWordMatcher.Match> hit = bannedWords.firstMatch(chatId, text);
        if (hit.isPresent()) {
            return BotReply.plain("⚠️ 消息含违禁内容（命中规则：" + hit.get().rule() + "），请文明交流。");
        }
        // 2) 自动回复
        return autoReply.replyFor(text).map(BotReply::plain).orElse(null);
    }
}
