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

/**
 * 主动通知的投递结果。是返回值不是异常：通知失败不改变已提交的状态迁移。
 * {@link #RECIPIENT_UNREACHABLE} 含「发不出去」与「算不出收件人」（操作者非当事人）两种情形——
 * 对调用方是同一件事：需发起方另行告知。
 */
public enum NotificationOutcome {

    /** 已送达对方。 */
    SENT,

    /** 未能送达（发送失败，或压根算不出收件人）。 */
    RECIPIENT_UNREACHABLE
}
