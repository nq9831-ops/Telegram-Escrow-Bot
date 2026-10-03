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
import org.telegram.telegrambots.meta.api.objects.User;
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
 * <h2>角色来源</h2>
 * <p>群内角色经 {@code MemberRolePort} 真实查询（Wave 1 起）；取不到时按最低的
 * {@link MemberRole#MEMBER}——权限只能收紧不能放松（fail-closed 方向）。
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
     * 周榜惰性推送（④ 差距 4）；{@code null} = 未启用（保持既有装配与测试零改动）。
     *
     * <p>挂在<b>这里</b>而不是 {@code BotDispatcher}：分发器一次只能返回一条回执，
     * 而"先推周榜、再答这条交互"需要两条消息——只有持有回复出口的本层做得到。
     */
    private final WeeklyBoardPush weeklyBoardPush;

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
        this(tokenConfig, dispatcher, reply, botUsername, webAppUrl, memberRolePort, null);
    }

    /**
     * @param weeklyBoardPush 周榜惰性推送（④ 差距 4）；{@code null} = 不启用（既有装配保持原行为）
     */
    public TelegramBotHandler(BotTokenConfig tokenConfig, BotDispatcher dispatcher,
                             BotReplyPort reply, String botUsername, String webAppUrl,
                             com.tg.escrow.core.MemberRolePort memberRolePort,
                             WeeklyBoardPush weeklyBoardPush) {
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
        this.weeklyBoardPush = weeklyBoardPush;
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
        // 群内可以「以频道身份」发言（匿名管理员 / 频道帖）：这时 from 为空，身份由 senderChat 承载。
        // 此前这类消息因 userId=0 在构造期被拒 → 整条消息（内容安全与命令）都不会被处理。
        Chat senderChat = message.getSenderChat();
        Message replyTo = message.getReplyToMessage();

        return new IncomingMessage(chatId, userId, messageId, message.getText(),
                mediaKindOf(message),
                document == null ? null : document.getFileName(),
                document == null ? null : document.getMimeType(),
                chatKindOf(message), senderChat != null, channelNameOf(senderChat),
                replyUserIdOf(replyTo), replyMessageIdOf(replyTo));
    }

    /**
     * 被回复消息的发送者 ID（0 = 无回复 / 不可辨识）。
     *
     * <p>刻意把 <b>bot 自己</b>从"可辨识的被回复者"里排除：用户回复一条 bot 回执再发命令时，
     * 若把 bot 当目标，「回复即指定目标」的交互会把 bot 当成操作对象（例如把它当卖方建单）——
     * 那不是任何用户想要的。排除后这类回复退化为"无目标"，命令回到参数缺失提示。
     * 频道帖（{@code from} 为空）同理不计。
     */
    private static long replyUserIdOf(Message replyTo) {
        if (replyTo == null || replyTo.getFrom() == null
                || Boolean.TRUE.equals(replyTo.getFrom().getIsBot())) {
            return 0L;
        }
        return replyTo.getFrom().getId();
    }

    /** 被回复消息的消息号（0 = 无回复）——/del 等按消息操作的命令据此工作。 */
    private static long replyMessageIdOf(Message replyTo) {
        if (replyTo == null || replyTo.getMessageId() == null) {
            return 0L;
        }
        return replyTo.getMessageId();
    }

    /**
     * 频道名：优先 {@code @username}，退回 {@code title}。
     *
     * <p>守卫按名字匹配允许名单，故取值要与部署者在配置里写的一致——人的书写习惯是
     * {@code @channel} 或显示名，两者都认（守卫自身还会忽略 {@code @} 前缀与大小写）。
     */
    private static String channelNameOf(Chat senderChat) {
        if (senderChat == null) {
            return null;
        }
        if (senderChat.getUserName() != null && !senderChat.getUserName().isBlank()) {
            return senderChat.getUserName();
        }
        return senderChat.getTitle();
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
     * 这条消息是否值得处理：有文本（命令需要）<b>或</b>有媒体（媒体消息经分发器一律不响应，
     * 保留此门禁只为让"回复媒体消息 + 命令"的回复对象可达）。
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
                // @username 仅在有真实发起者时可得（匿名管理员以群组身份发送，getFrom 为 null）。
                CommandActor actor = new CommandActor(userId, memberRolePort.roleOf(chatId, userId),
                        message.getFrom() == null ? null : message.getFrom().getUserName());
                send(chatId, dispatcher.handle(toIncoming(message), actor));
                // 周榜惰性推送（④ 差距 4）：搭在群内本来就有的交互上——没有调度器也能每周推一次。
                // 放在应答**之后**：答复本次交互优先，周榜是搭车，不该挤在前面。
                if (weeklyBoardPush != null && chatId < 0) {
                    weeklyBoardPush.maybePush(chatId).ifPresent(board -> reply.sendText(chatId, board));
                }
            } else if (update.hasCallbackQuery()) {
                // 按钮点击 → 解码成等价命令 → 走与文本消息同一条分发链。
                // 按钮不是授权：actor 取点击者本人，命令层的权限/状态守卫照旧执行。
                handleCallback(update.getCallbackQuery());
            }
        } finally {
            if (callbackId != null) {
                // 不变量：每个 callback 必应答——放在 finally，异常路径也不例外
                reply.ackCallback(callbackId);
            }
        }
    }

    /**
     * 按回执自带的意图选择出口：<b>动作按钮 › 表单按钮 › 纯文本</b>。
     *
     * <p>顺序刻意定死：带动作按钮的回执<b>不再</b>附表单按钮——一条消息上同时挂
     * "去表单"和"就地操作"两种按钮，用户分不清哪条是主路径。
     */
    private void send(long chatId, BotReply out) {
        if (out == null || out.text() == null) {
            return;
        }
        if (!out.buttons().isEmpty()) {
            reply.sendTextWithButtons(chatId, out.text(), out.buttons());
        } else if (out.offerWebApp() && webAppUrl != null && !webAppUrl.isBlank()) {
            // 用法/帮助场景：附「打开表单」按钮，用户不必记命令语法
            reply.sendTextWithWebApp(chatId, out.text(), WEBAPP_BUTTON_TEXT, webAppUrl);
        } else {
            reply.sendText(chatId, out.text());
        }
    }

    /**
     * 按钮点击处理：把 {@code callback_data} 解码成等价命令文本，交给同一个分发器。
     *
     * <h2>按钮与命令严格等价（含二次确认）</h2>
     * <p>按钮解出的就是一条普通命令文本，因此走的是与文本输入<b>完全相同</b>的分发与守卫链——
     * 不额外给按钮造第二条语义路径。资金动作（release / refund）的两步确认也由命令层承担：
     * 第 1 击（{@code rl:3}）只<b>发起</b>、回一条带「确认执行」按钮的回执，第 2 击（三段形态
     * {@code rl:3:y} → 解码为 {@code /escrow release 3 --yes}）命中确认槽才执行；
     * 其余动作仍是一击即执行。误点的防护放在按钮<em>文案</em>里（自带动作与订单号）。
     *
     * <p><b>按钮不是授权</b>：actor 取点击者本人，命令层的「仅买方 / 仅卖方 / 状态守卫」
     * 照旧执行——点别人的按钮只会得到一句可读的拒绝。
     *
     * <p>回执发回<b>点击所在的会话</b>；拿不到所在消息时退回私聊点击者本人
     * （宁可换会话送达，也不静默丢弃回执）。
     */
    private void handleCallback(org.telegram.telegrambots.meta.api.objects.CallbackQuery query) {
        if (query.getFrom() == null) {
            // 无发起者 = 无身份可判权限。不做任何动作；finally 仍会应答（按钮不转圈）。
            return;
        }
        java.util.Optional<String> command = ActionCodec.decode(query.getData());
        if (command.isEmpty()) {
            // 未知/畸形数据（改版前的旧按钮、被伪造的 data）：不执行任何动作
            return;
        }
        long userId = query.getFrom().getId();
        long chatId = query.getMessage() != null ? query.getMessage().getChatId() : userId;
        long messageId = query.getMessage() != null ? query.getMessage().getMessageId() : 0L;
        CommandActor actor = new CommandActor(userId, memberRolePort.roleOf(chatId, userId),
                query.getFrom().getUserName());
        send(chatId, dispatcher.handle(
                com.tg.escrow.core.IncomingMessage.text(chatId, userId, messageId, command.get()),
                actor));
    }
}
