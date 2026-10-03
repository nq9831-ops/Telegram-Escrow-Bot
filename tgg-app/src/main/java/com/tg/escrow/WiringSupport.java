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
import com.tg.escrow.core.AdminReviewNotifier;

import java.util.List;

/**
 * 装配辅助（package-private 工具，不注册任何 bean）——各 {@code *Wiring} 配置类共享的解析逻辑。
 *
 * <p>从 {@link BotWiring} 拆分出的公共静态方法（2026-10-03 拆分时逐字保留）：
 * {@code splitCsv}（逗号分隔配置解析）与 {@code reviewNotifierOrNull}（REVIEW 复核通知通道三态）。
 */
final class WiringSupport {

    private WiringSupport() {
    }

    /** 逗号分隔配置解析：空白/空串 → 空列表；逐项 trim、滤空项。 */
    static List<String> splitCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /**
     * 复核通知通道三态（②）：未配置/空 = null = 不启用（REVIEW 只留痕，行为与从前一致）；
     * 配置了管理会话 ID = 启用。配置非法（非数字）启动期抛——与风险权重同一 fail-fast 取向。
     * 供入群复核（{@code MemberJoinHandler}）与欺诈建议（{@code FraudLinkageService}）同源使用。
     */
    static AdminReviewNotifier reviewNotifierOrNull(BotReplyPort reply, String reviewNotifyChat) {
        if (reviewNotifyChat == null || reviewNotifyChat.isBlank()) {
            return null;
        }
        long notifyChatId;
        try {
            notifyChatId = Long.parseLong(reviewNotifyChat.trim());
        } catch (NumberFormatException ex) {
            throw new TggException("tgg.risk.review-notify-chat：「" + reviewNotifyChat
                    + "」不是合法的 Telegram 会话 ID（应为数字）");
        }
        return new BotReplyAdminReviewNotifier(reply, notifyChatId);
    }
}
