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
import com.tg.escrow.core.NoticePolicy;
import com.tg.escrow.core.SilenceWindow;
import com.tg.escrow.escrow.EscrowOrder;

import java.time.Clock;

/**
 * 交易对手方通知：把一次状态迁移主动告诉「没有发起该次操作的那一方」。
 *
 * <h2>唯一的一条规则</h2>
 * <p>收件人 = 订单当事人中 <b>≠</b> 操作者的那一方。服务调用成功返回后订单必然已存在
 * （建单亦然），所以这条规则覆盖全部迁移，<b>不依赖命令措辞</b>、不需要逐事件配收件人。
 * 操作者不是当事人时算不出收件人——那时<b>一条都不发</b>（返回
 * {@link NotificationOutcome#RECIPIENT_UNREACHABLE}），而不是猜一个。
 *
 * <h2>失败方向：绝不让通知拖垮迁移</h2>
 * <p>调用本类的时机是"状态迁移已提交成功之后"。因此发送失败（对方未与机器人会话、被拉黑、
 * 网络）一律<b>吞掉并降级为返回值</b>，绝不上抛——否则调用方会以为迁移失败，而迁移早已落库。
 * 调用方据返回值决定"要不要回告发起方一句"。
 *
 * <h2>静默窗口的作用点</h2>
 * <p>{@link SilenceWindow} 只压制 {@link TradeEvent.Kind#ACTION_NEEDED} 档的<b>响铃</b>；
 * 资金/争议档不受其影响。注意 Telegram 的 {@code disable_notification} 只关响铃、不动送达——
 * 静默不等于信息丢失。窗口未配置（{@code null}）时不做任何静默判定。
 */
public final class TradeNotifier {

    private final BotReplyPort reply;
    private final Clock clock;
    /** 可空：未配置即不做静默判定。 */
    private final SilenceWindow silenceWindow;

    /**
     * @param reply         回复出口（主动通知与回执共用同一端口——Telegram 依赖仍只有一处）
     * @param clock         时钟（静默窗口判定用，注入使判定可测）
     * @param silenceWindow 静默窗口；{@code null} 表示不启用
     */
    public TradeNotifier(BotReplyPort reply, Clock clock, SilenceWindow silenceWindow) {
        if (reply == null) {
            throw new TggException("交易通知：回复出口不可为空");
        }
        if (clock == null) {
            throw new TggException("交易通知：时钟不可为空");
        }
        this.reply = reply;
        this.clock = clock;
        this.silenceWindow = silenceWindow;
    }

    /**
     * 通知对方：一次迁移发生了。
     *
     * @param order   迁移后的订单（须已落库，即 id 非空）
     * @param actorId 发起该次迁移的用户
     * @param event   迁移对应的事件
     * @return {@link NotificationOutcome#SENT} 或 {@link NotificationOutcome#RECIPIENT_UNREACHABLE}
     * @throws TggException 订单或事件缺失（调用方编码错误，不静默降级成"不发"）
     */
    public NotificationOutcome notify(EscrowOrder order, long actorId, TradeEvent event) {
        if (order == null) {
            throw new TggException("交易通知：订单不可为空");
        }
        if (event == null) {
            throw new TggException("交易通知：事件不可为空");
        }

        long recipient = recipientOf(order, actorId);
        if (recipient < 0) {
            return NotificationOutcome.RECIPIENT_UNREACHABLE;
        }

        try {
            reply.sendText(recipient, event.render(order), effectivePolicy(event));
            return NotificationOutcome.SENT;
        } catch (RuntimeException ex) {
            // 端口契约上失败会被包成 TggException；这里连 RuntimeException 一起收，
            // 因为"通知发不出去"绝不能变成"迁移失败"——代价是这里必须留痕，不许静默。
            System.err.println("交易通知：发往 " + recipient + " 的「" + event + "」未送达："
                    + ex.getMessage());
            return NotificationOutcome.RECIPIENT_UNREACHABLE;
        }
    }

    /** 收件人 = 当事人中 ≠ 操作者的一方；操作者不是当事人时返回 -1。 */
    private static long recipientOf(EscrowOrder order, long actorId) {
        if (order.getBuyerUserId() == actorId) {
            return order.getSellerUserId();
        }
        if (order.getSellerUserId() == actorId) {
            return order.getBuyerUserId();
        }
        return -1L;
    }

    /** 事件默认策略，若处于静默窗口且该档可被压制，则把响铃关掉（保留可见范围与留存语义）。 */
    private NoticePolicy effectivePolicy(TradeEvent event) {
        NoticePolicy base = event.policy();
        if (!event.silenceable() || silenceWindow == null
                || !silenceWindow.isSilenced(clock.instant())) {
            return base;
        }
        return new NoticePolicy(base.ephemerality(), true, base.retained());
    }
}
