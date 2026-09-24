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

import com.tg.escrow.core.ChatKind;
import com.tg.escrow.core.CommandActor;
import com.tg.escrow.core.IncomingMessage;
import com.tg.escrow.core.MemberRole;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.objects.Document;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;

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

    /** 「打开表单」按钮文字。 */
    private static final String WEBAPP_BUTTON_TEXT = "📝 打开表单";

    private final BotTokenConfig tokenConfig;
    private final BotDispatcher dispatcher;
    private final BotReplyPort reply;
    private final String botUsername;
    private final String webAppUrl;
    private final com.tg.escrow.core.MemberRolePort memberRolePort;

    /**
     * @param tokenConfig    token 配置（构造期已 fail-fast）
     * @param dispatcher     命令分发器
     * @param reply          回复出口（TelegramClient 适配器实现）
     * @param botUsername    本 bot 用户名（不含 {@code @}）
     * @param webAppUrl      Mini App 表单地址（HTTPS）；为空则「引导表单」的回执退化为纯文本、不带按钮
     * @param memberRolePort 群内角色查询端口（Wave 1：真实角色，替代此前的硬编码 MEMBER）
     */
    public TelegramBotHandler(BotTokenConfig tokenConfig, BotDispatcher dispatcher,
                             BotReplyPort reply, String botUsername, String webAppUrl,
                             com.tg.escrow.core.MemberRolePort memberRolePort) {
        if (tokenConfig == null || dispatcher == null || reply == null) {
            throw new com.tg.escrow.common.TggException("Bot 接线：token/分发器/回复出口均不可为空");
        }
        if (memberRolePort == null) {
            throw new com.tg.escrow.common.TggException("Bot 接线：角色查询端口不可为空（缺它则处置权限判不了）");
        }
        this.tokenConfig = tokenConfig;
        this.dispatcher = dispatcher;
        this.reply = reply;
        this.botUsername = botUsername;
        this.webAppUrl = webAppUrl;
        this.memberRolePort = memberRolePort;
    }

    /** bot token（10.x 架构中 token 由注册方（TelegramBotsLongPollingApplication）持有，非接口方法）。 */
    public String getBotToken() {
        return tokenConfig.token();
    }

    /** 本 bot 用户名（不含 {@code @}）。 */
    public String getBotUsername() {
        return botUsername;
    }

    /**
     * 把 Telegram 的 {@link Message} 映射成本项目的 {@link IncomingMessage}（Wave 2）。
     *
     * <p>包级可见，供单测直接打表——映射是最容易悄悄错的一环（媒体类型判错会让过滤
     * 形同虚设、或误杀正常消息），必须能被独立验证，而不是只能靠真机观察。
     *
     * @throws com.tg.escrow.common.TggException 消息为空或缺少 ID（缺 ID 则处置时无法定位）
     */
    static IncomingMessage toIncoming(Message message) {
        if (message == null) {
            throw new com.tg.escrow.common.TggException("Bot 接线：消息不可为空");
        }
        Integer messageId = message.getMessageId();
        if (messageId == null) {
            throw new com.tg.escrow.common.TggException("Bot 接线：消息缺少 ID（处置时无法定位）");
        }
        long chatId = message.getChat() == null ? 0L : message.getChatId();
        long userId = message.getFrom() == null ? 0L : message.getFrom().getId();
        Document document = message.getDocument();

        return new IncomingMessage(chatId, userId, messageId, message.getText(),
                mediaKindOf(message),
                document == null ? null : document.getFileName(),
                document == null ? null : document.getMimeType(),
                chatKindOf(message));
    }

    /**
     * Telegram {@code Chat.type} → 本项目的 {@link ChatKind}。
     *
     * <p>无法识别时归 {@link ChatKind#CHANNEL}——该值在
     * {@link ChatKind#moderatable()} 下为"不适用"，即<b>不</b>执行删消息。
     * 方向是刻意的：无法确认是群，就不做破坏性动作。
     */
    private static ChatKind chatKindOf(Message message) {
        Chat chat = message.getChat();
        if (chat == null || chat.getType() == null) {
            return ChatKind.CHANNEL;
        }
        return switch (chat.getType()) {
            case "private" -> ChatKind.PRIVATE;
            case "group" -> ChatKind.GROUP;
            case "supergroup" -> ChatKind.SUPERGROUP;
            default -> ChatKind.CHANNEL;
        };
    }

    /** Telegram 的媒体字段 → 本项目的媒体种类。无媒体即 {@code NONE}。 */
    private static IncomingMessage.MediaKind mediaKindOf(Message message) {
        if (message.hasDocument()) {
            return IncomingMessage.MediaKind.DOCUMENT;
        }
        if (message.hasPhoto()) {
            return IncomingMessage.MediaKind.PHOTO;
        }
        if (message.hasVideo()) {
            return IncomingMessage.MediaKind.VIDEO;
        }
        if (message.hasAudio() || message.hasVoice() || message.hasSticker()
                || message.hasVideoNote()) {
            return IncomingMessage.MediaKind.OTHER;
        }
        return IncomingMessage.MediaKind.NONE;
    }

    /**
     * 这条消息是否值得处理：有文本（命令/自动回复需要）<b>或</b>有媒体（内容安全过滤需要）。
     *
     * <p>此前只看 {@code hasText()}，于是带链接说明的纯图片/文件消息会整条被跳过——
     * 媒体过滤永远没机会上场。放宽门禁正是让检测器能真正生效的前提之一。
     */
    private static boolean isProcessable(Message message) {
        return message.hasText() || message.hasPhoto() || message.hasDocument()
                || message.hasVideo() || message.hasAudio() || message.hasVoice()
                || message.hasSticker() || message.hasVideoNote();
    }

    @Override
    public void consume(Update update) {
        if (update == null) {
            return;
        }
        String callbackId = update.hasCallbackQuery() ? update.getCallbackQuery().getId() : null;
        try {
            if (update.hasMessage() && isProcessable(update.getMessage())) {
                Message message = update.getMessage();
                long chatId = message.getChatId();
                long userId = message.getFrom() != null ? message.getFrom().getId() : 0L;
                // 角色取自群内真实状态（查不到即退 MEMBER——权限只收紧不放松）
                CommandActor actor = new CommandActor(userId, memberRolePort.roleOf(chatId, userId));
                BotReply out = dispatcher.handle(toIncoming(message), actor);
                if (out != null && out.text() != null) {
                    if (out.offerWebApp() && webAppUrl != null && !webAppUrl.isBlank()) {
                        // 用法/帮助场景：附「打开表单」按钮，用户不必记命令语法
                        reply.sendTextWithWebApp(chatId, out.text(), WEBAPP_BUTTON_TEXT, webAppUrl);
                    } else {
                        reply.sendText(chatId, out.text());
                    }
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
