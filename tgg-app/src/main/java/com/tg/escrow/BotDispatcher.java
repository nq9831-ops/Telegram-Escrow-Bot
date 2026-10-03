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
import com.tg.escrow.core.IncomingMessage;

import java.util.Optional;

/**
 * Bot 命令分发器（S1 路由）。
 *
 * <p>命令：{@code /escrow …} → {@link TradeCommandHandler}；其余命令 → 帮助；
 * 非命令消息不响应。发给别的 bot 的 {@code @} 后缀命令不响应。
 * 纯逻辑，Telegram 胶水在 {@code TelegramBotHandler}。
 */
public final class BotDispatcher {

    private static final String HELP = "我是担保交易助手。用法：/escrow invite <金额> <币种> 生成邀请链接"
            + "（对方点开即接单）；/escrow create <卖方ID> <金额> <币种> 预览风险，"
            + "确认后 /escrow confirm 创建交易；/escrow lock|deliver|release|refund|dispute <订单号> "
            + "推进交易；/escrow status <订单号> 查询状态。";

    private final TradeCommandHandler tradeHandler;
    private final String botUsername;

    /**
     * @param tradeHandler 交易命令处理器
     * @param botUsername  本 bot 用户名（不含 {@code @}）
     */
    public BotDispatcher(TradeCommandHandler tradeHandler, String botUsername) {
        if (tradeHandler == null || botUsername == null) {
            throw new TggException("命令分发：处理器与 bot 用户名均不可为空");
        }
        this.tradeHandler = tradeHandler;
        this.botUsername = botUsername;
    }

    /**
     * 处理一条入站消息——<b>唯一入口</b>。
     *
     * <p>刻意<b>不</b>提供「只吃文本」的重载：交易命令的「回复式指定目标」需要回复对象
     * （{@code replyToUserId}），一个拿不到它的入口会把功能削掉一半。要处理消息，就给整条消息。
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

        // 交易群惰性归档（ET-39，不引入调度器）：静默期满的群在下一次交互时结算——
        // 公告归档并退群；本条消息就此归档、不再按原语义处理（群已收档）。
        // 注意：归档后 bot 已退群，**不能**再往该群发回执（否则 403 "bot is not a member"），
        // 故这里返回 null（不响应）——公告已在归档时发到群里，无需也不该再补一条群内回执。
        if (chatId < 0 && tradeHandler.reapIfDue(chatId)) {
            return null;
        }

        String text = message.text();
        if (text == null || text.isBlank()) {
            return null;   // 无文本消息：不响应
        }
        Optional<com.tg.escrow.core.BotCommand> parsed = CommandParser.parse(text, botUsername);
        // 频道身份发言没有"个人"这个主体：命令的语义是「某个用户要做什么」，
        // 以 userId=0 去执行会凭空造出一个 0 号用户（下单、取消都是真实副作用）。
        // 故这类消息**不路由命令**。
        if (parsed.isPresent() && !message.senderIsChannel()) {
            return handleCommand(parsed.get(), actor, message);
        }
        return null;
    }

    /** 命令路由：交易命令 → 交易处理器；其余命令 → 帮助。 */
    private BotReply handleCommand(com.tg.escrow.core.BotCommand cmd, CommandActor actor,
                                   IncomingMessage message) {
        if (tradeHandler.canHandle(cmd)) {
            // 回复目标透传：回复对方消息 + /escrow create <金额> <币种> = 免输卖方 ID；
            // handleReply 内部另区分：「用法」回执 → 引导去表单；其余回执 → 按角色附动作按钮
            return tradeHandler.handleReply(cmd, actor, message.chatId(),
                    message.replyToUserId());
        }
        return BotReply.withWebApp(HELP);
    }
}
