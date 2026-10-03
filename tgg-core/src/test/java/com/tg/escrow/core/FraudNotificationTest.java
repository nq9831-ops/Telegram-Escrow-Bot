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

import com.tg.escrow.core.FraudLinkage.Action;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 欺诈联动（ET-04）管理员建议文案的行为固定测试。
 *
 * <p>把 {@link FraudLinkage.Action} 翻成管理员看得懂的「建议封禁/建议关注/不动作」，
 * 并由 {@link AdminReviewNotifier#fraudMessage} 组装成可行动文案（给出哪个群、谁、什么建议，
 * 且显式声明「仅建议不执行」）。实现与测试引用同一纯函数，不各拼各的。
 */
class FraudNotificationTest {

    @Test
    @DisplayName("Action 携带管理员可读标签：建议封禁 / 建议关注 / 不动作")
    void actionLabels() {
        assertThat(Action.BAN_RECOMMENDED.label()).isEqualTo("建议封禁");
        assertThat(Action.WATCHLIST.label()).isEqualTo("建议关注");
        assertThat(Action.NONE.label()).isEqualTo("不动作");
    }

    @Test
    @DisplayName("欺诈建议文案钉住可行动事实：群、用户、建议标签 + 「仅建议」")
    void fraudMessagePinsActionableFacts() {
        String msg = AdminReviewNotifier.fraudMessage(2002L, 9001L, Action.BAN_RECOMMENDED);

        assertThat(msg).contains("群 2002").contains("9001").contains("建议封禁").contains("仅建议");
    }

    @Test
    @DisplayName("建议关注档文案同样可行动 —— 标签随 Action 变化")
    void watchlistMessageCarriesItsOwnLabel() {
        String msg = AdminReviewNotifier.fraudMessage(3003L, 4242L, Action.WATCHLIST);

        assertThat(msg).contains("群 3003").contains("4242").contains("建议关注").contains("仅建议");
    }

    @Test
    @DisplayName("默认实现无投递能力时不谎报 —— 默认 notifyFraudRecommendation 可安全调用（不抛）")
    void defaultNotifierDoesNotClaimDelivery() {
        // 单抽象方法实现（只实现 notifyReview）仍能编译，说明新增方法是非破坏性的默认方法
        AdminReviewNotifier noop = (chatId, userId, assessment) -> { };

        noop.notifyFraudRecommendation(2002L, 9001L, Action.WATCHLIST);
    }

    @Test
    @DisplayName("交易侧文案：给「群+双方 ID+建议+仅建议」——受众是交易双方互刷，不是入群新成员")
    void tradeMessageNamesBothSidesAndDisclaimsExecution() {
        String msg = AdminReviewNotifier.fraudTradeMessage(-100123L, 2002L, 1001L, Action.WATCHLIST);

        assertThat(msg).contains("群 -100123").contains("2002").contains("1001")
                .contains("建议关注").contains("仅建议");
    }
}
