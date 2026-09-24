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

import com.tg.escrow.core.CommandActor;
import com.tg.escrow.core.MemberRole;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.objects.Update;

/**
 * Telegram Bot 接线（S1）：Update → 分发器 → 回执。纯胶水，不含业务判断。
 *
 * <h2>answerCallbackQuery 不变量（硬约束）</h2>
 * <p>每个 callback query <b>必须应答</b>——否则客户端按钮永久转圈直到超时（正确性问题，
 * 非体验优化，见 {@code docs/requirements/10-交互细节.md}）。故应答放在 {@code finally}：
 * 无论处理成功或抛异常都应答，<b>结构上杜绝遗漏</b>。
 *
 * <h2>角色取值保守</h2>
 * <p>当前从 Update 仅能可靠取到 userId，群内角色暂按最低的 {@link MemberRole#MEMBER}——
 * 权限只能收紧不能放松（管理员命令在真实角色接入前一律不可执行，fail-closed 方向）。
 * 真实角色需调 getChatMember（上线接入时升级）。
 *
 * <p><b>运行期行为（发送、长轮询连接）未经本地验证</b>——依赖 Telegram 网络与真实 token，
 * 属线上阶段验收项。
 */
public final class TelegramBotHandler implements LongPollingSingleThreadUpdateConsumer {

    private final BotTokenConfig tokenConfig;
    private final BotDispatcher dispatcher;
    private final BotReplyPort reply;
    private final String botUsername;

    /**
     * @param tokenConfig token 配置（构造期已 fail-fast）
     * @param dispatcher  命令分发器
     * @param reply       回复出口（TelegramClient 适配器实现）
     * @param botUsername 本 bot 用户名（不含 {@code @}）
     */
    public TelegramBotHandler(BotTokenConfig tokenConfig, BotDispatcher dispatcher,
                             BotReplyPort reply, String botUsername) {
        if (tokenConfig == null || dispatcher == null || reply == null) {
            throw new com.tg.escrow.common.TggException("Bot 接线：token/分发器/回复出口均不可为空");
        }
        this.tokenConfig = tokenConfig;
        this.dispatcher = dispatcher;
        this.reply = reply;
        this.botUsername = botUsername;
    }

    /** bot token（10.x 架构中 token 由注册方（TelegramBotsLongPollingApplication）持有，非接口方法）。 */
    public String getBotToken() {
        return tokenConfig.token();
    }

    /** 本 bot 用户名（不含 {@code @}）。 */
    public String getBotUsername() {
        return botUsername;
    }

    @Override
    public void consume(Update update) {
        if (update == null) {
            return;
        }
        String callbackId = update.hasCallbackQuery() ? update.getCallbackQuery().getId() : null;
        try {
            if (update.hasMessage() && update.getMessage().hasText()) {
                long chatId = update.getMessage().getChatId();
                long userId = update.getMessage().getFrom() != null
                        ? update.getMessage().getFrom().getId() : 0L;
                // 角色保守取 MEMBER：权限只能收紧，不放松（真实角色上线接入后升级）
                CommandActor actor = new CommandActor(userId, MemberRole.MEMBER);
                String text = update.getMessage().getText();
                String response = dispatcher.handle(text, actor);
                if (response != null) {
                    reply.sendText(chatId, response);
                }
            }
            // 按钮交互的命令扩展在 S3 接入；当前 callback 仅应答
        } finally {
            if (callbackId != null) {
                // 不变量：每个 callback 必应答——放在 finally，异常路径也不例外
                reply.ackCallback(callbackId);
            }
        }
    }
}
