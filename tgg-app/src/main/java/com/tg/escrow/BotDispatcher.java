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
import com.tg.escrow.core.IncomingMessage;
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
            + "推进交易；/escrow status <订单号> 查询状态。"
            + "群管理（管理员）：/kick <用户ID>、/ban <用户ID>、/mute <用户ID> <分钟>、/del <消息ID>、"
            + "/warn <用户ID>、/unwarn <用户ID>。";

    private final TradeCommandHandler tradeHandler;
    private final String botUsername;
    private final BannedWordRegistry bannedWords;
    private final KeywordAutoReply autoReply;
    private final ModerationCommandHandler moderationHandler;
    private final MessageGuardService guardService;

    /**
     * @param tradeHandler      交易命令处理器
     * @param botUsername       本 bot 用户名（不含 {@code @}）
     * @param bannedWords       违禁词注册表
     * @param autoReply         自动回复（GM-17 的生产消费者）
     * @param moderationHandler 群管理命令处理器（/kick /ban /mute /del /warn /unwarn）
     * @param guardService      内容安全处置（链接/媒体/限流 → 删消息 + 累计警告）
     */
    public BotDispatcher(TradeCommandHandler tradeHandler, String botUsername,
                         BannedWordRegistry bannedWords, KeywordAutoReply autoReply,
                         ModerationCommandHandler moderationHandler,
                         MessageGuardService guardService) {
        if (tradeHandler == null || bannedWords == null || autoReply == null) {
            throw new TggException("命令分发：处理器/违禁词注册表/自动回复均不可为空");
        }
        if (moderationHandler == null) {
            throw new TggException("命令分发：群管理命令处理器不可为空");
        }
        if (guardService == null) {
            throw new TggException("命令分发：内容安全处置不可为空");
        }
        this.tradeHandler = tradeHandler;
        this.botUsername = botUsername;
        this.bannedWords = bannedWords;
        this.autoReply = autoReply;
        this.moderationHandler = moderationHandler;
        this.guardService = guardService;
    }

    /**
     * 处理一条入站消息——<b>唯一入口</b>。
     *
     * <p>刻意<b>不</b>提供「只吃文本」的重载：内容安全判定需要消息号（删消息）与媒体信息，
     * 一个拿不到这些的入口会变成绕过安全判定的旁路。要处理消息，就给整条消息。
     *
     * <p>路由顺序（定死）：命令 → 违禁词 → 内容安全（链接/媒体/限流）→ 自动回复。
     * 安全动作一律排在应答之前——一条带钓鱼链接的消息不该先看到欢迎语。
     *
     * @param message 入站消息
     * @param actor   发起人（角色取自群内真实状态）
     * @return 回执；{@code null} 表示<b>不响应</b>
     */
    public BotReply handle(IncomingMessage message, CommandActor actor) {
        if (message == null || actor == null) {
            return null;
        }
        long chatId = message.chatId();
        String text = message.text();
        boolean hasText = text != null && !text.isBlank();

        if (hasText) {
            Optional<com.tg.escrow.core.BotCommand> parsed = CommandParser.parse(text, botUsername);
            if (parsed.isPresent()) {
                return handleCommand(parsed.get(), actor, chatId);
            }
            // 1) 违禁词优先（安全 > 应答）
            Optional<BannedWordMatcher.Match> hit = bannedWords.firstMatch(chatId, text);
            if (hit.isPresent()) {
                return BotReply.plain("⚠️ 消息含违禁内容（命中规则：" + hit.get().rule() + "），请文明交流。");
            }
        }

        // 2) 内容安全（链接/媒体/限流）——排在自动回复之前
        try {
            Optional<String> screened = guardService.screen(message);
            if (screened.isPresent()) {
                return BotReply.plain(screened.get());
            }
        } catch (TggException ex) {
            // 处置失败必须让上层知道——吞掉会让人以为消息已被清理
            return BotReply.plain("⚠️ 内容安全处置失败：" + ex.getMessage());
        }

        // 3) 自动回复（仅文本消息）
        if (!hasText) {
            return null;
        }
        return autoReply.replyFor(text).map(BotReply::plain).orElse(null);
    }

    /** 命令路由：处置命令优先于业务命令（安全动作不该排队在业务应答之后）。 */
    private BotReply handleCommand(com.tg.escrow.core.BotCommand cmd, CommandActor actor, long chatId) {
        if (moderationHandler.canHandle(cmd)) {
            return BotReply.plain(moderationHandler.handle(cmd, actor, chatId));
        }
        if (tradeHandler.canHandle(cmd)) {
            String reply = tradeHandler.handle(cmd, actor);
            // "/escrow" 无子命令或参数不全 → 用法说明：正是该引导用户去表单的时刻
            return TradeCommandHandler.USAGE.equals(reply)
                    ? BotReply.withWebApp(reply)
                    : BotReply.plain(reply);
        }
        return BotReply.withWebApp(HELP);
    }
}
