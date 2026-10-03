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
package com.tg.escrow.escrow;

import com.tg.escrow.common.EscrowException;

/**
 * 邀请接单失败——带<b>机器可判的拒绝原因</b>，便于入口层映射到不同的 HTTP 语义/回执。
 *
 * <h2>为什么单独一个类型 + 原因枚举</h2>
 * <p>「邀请不存在 / 已被接受 / 已过期 / 自邀自接 / 发起方当前不可承接」是<b>五种不同的结局</b>，
 * 用户该看到的话不同（重试？换人？太晚？），入口层也要落到不同状态码（404/409/410/400）。
 * 若都压成 {@link EscrowException} 靠比对文案区分，一改措辞就静默失效——那正是本仓
 * {@link ConcurrentOrderUpdateException} 立下的先例：技术/业务失败要能被上层结构化识别。
 */
public class TradeInviteException extends EscrowException {

    /** 邀请不可接受的原因。 */
    public enum Reason {
        /** 令牌无对应邀请（不存在 / 已被清理）。 */
        NOT_FOUND,
        /** 邀请已被他人接受（一次性）。 */
        ALREADY_ACCEPTED,
        /** 邀请已过有效期。 */
        EXPIRED,
        /** 发起人试图接受自己的邀请。 */
        SELF_ACCEPT,
        /** 发起方当前不满足准入门禁（并发上限 / 冷却 / 争议）。 */
        NOT_ADMITTED
    }

    private final Reason reason;

    public TradeInviteException(Reason reason, String message) {
        super(message);
        if (reason == null) {
            throw new EscrowException("邀请失败缺少原因");
        }
        this.reason = reason;
    }

    /** 机器可判的失败原因。 */
    public Reason reason() {
        return reason;
    }
}
