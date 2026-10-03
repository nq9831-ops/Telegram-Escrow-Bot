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

import com.tg.escrow.core.AdminReviewNotifier;
import com.tg.escrow.core.FraudLinkage;
import com.tg.escrow.core.RiskAssessment;

/**
 * {@link AdminReviewNotifier} 的生产实现：把复核请求与欺诈联动建议投递到部署者配置的管理会话。
 *
 * <p>只做「拼文案 + sendText」一件事的两面（复核 / 欺诈建议）；两种文案都由
 * {@link AdminReviewNotifier} 的静态工厂同源生成（实现与测试引用同一纯函数，不各拼各的）。
 * 发送失败抛出——由 {@code MemberJoinHandler} 兜底留痕，这里不吞异常（吞掉就等于谎报已通知）。
 */
final class BotReplyAdminReviewNotifier implements AdminReviewNotifier {

    private final BotReplyPort reply;
    private final long notifyChatId;

    BotReplyAdminReviewNotifier(BotReplyPort reply, long notifyChatId) {
        this.reply = reply;
        this.notifyChatId = notifyChatId;
    }

    @Override
    public void notifyReview(long sourceChatId, long userId, RiskAssessment assessment) {
        reply.sendText(notifyChatId, AdminReviewNotifier.message(sourceChatId, userId, assessment));
    }

    /**
     * 欺诈联动建议（ET-04）——投递「建议封禁/建议关注」到同一管理会话。
     *
     * <p>与 {@link #notifyReview} 同一出口、同一文案工厂（{@link AdminReviewNotifier#fraudMessage}）：
     * <b>只建议不执行</b>，管理员据以人工核实后自行处置（执行走 {@code ModerationOrchestrator}）。
     */
    @Override
    public void notifyFraudRecommendation(long sourceChatId, long userId,
                                           FraudLinkage.Action recommendation) {
        reply.sendText(notifyChatId, AdminReviewNotifier.fraudMessage(sourceChatId, userId, recommendation));
    }

    @Override
    public void notifyFraudTradeRecommendation(long sourceChatId, long buyerId, long sellerId,
                                                FraudLinkage.Action recommendation) {
        reply.sendText(notifyChatId,
                AdminReviewNotifier.fraudTradeMessage(sourceChatId, buyerId, sellerId, recommendation));
    }
}
