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
package com.tg.escrow.core;

/**
 * 管理员「需人工介入」通知端口——REVIEW 档（GM-25）与欺诈建议（ET-04，仅建议不执行）两条出口。
 * 语义要点：①<b>明文 ID 是刻意取舍</b>——脱敏的通知无法处置（管理员无从核实），暴露面由
 * 部署者配置的管理会话承担（不进群、不进公开输出），日志侧仍全程脱敏；②默认实现<b>不投递也不谎报</b>
 * 已通知（与 REVIEW 未配置时「只留痕」同一取向）；③只建议不执行——封禁仍走 ModerationOrchestrator。
 */
public interface AdminReviewNotifier {

    /** 请求管理员复核一名新成员。 */
    void notifyReview(long sourceChatId, long userId, RiskAssessment assessment);

    /** 复核请求文案（纯函数，实现与测试同源）——必须给「哪个群、谁、为什么」。 */
    static String message(long sourceChatId, long userId, RiskAssessment assessment) {
        return "⚠️ 入群风控待复核：群 " + sourceChatId + " 新成员 " + userId
                + "（分数 " + assessment.score() + "，命中 " + assessment.hits()
                + "）。请人工确认。";
    }

    /** 欺诈联动建议（入群场景）；默认实现不投递（不假装已通知）。 */
    default void notifyFraudRecommendation(long sourceChatId, long userId,
                                           FraudLinkage.Action recommendation) {
    }

    /** 欺诈建议文案（纯函数）——必须给「哪个群、谁、什么建议」并显式声明「仅建议」。 */
    static String fraudMessage(long sourceChatId, long userId, FraudLinkage.Action recommendation) {
        return "⚠️ 欺诈联动建议：群 " + sourceChatId + " 新成员 " + userId
                + " —— " + recommendation.label() + "（仅建议，请管理员核实后处置）。";
    }

    /** 交易侧欺诈建议（ET-04 互刷信号，交易达成场景）；默认实现不投递。 */
    default void notifyFraudTradeRecommendation(long sourceChatId, long buyerId, long sellerId,
                                                FraudLinkage.Action recommendation) {
    }

    /** 交易侧欺诈建议文案（纯函数）——必须给「哪个群、谁和谁、什么建议」并声明「仅建议」。 */
    static String fraudTradeMessage(long sourceChatId, long buyerId, long sellerId,
                                    FraudLinkage.Action recommendation) {
        return "⚠️ 欺诈联动建议：群 " + sourceChatId + " 交易双方 " + buyerId + " ↔ " + sellerId
                + " 互相高度集中 —— " + recommendation.label() + "（仅建议，请管理员核实后处置）。";
    }
}
