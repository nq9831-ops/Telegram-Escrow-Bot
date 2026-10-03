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
 * 邀请创建结果——<b>创建成功带邀请，被拒带裁决</b>，两者互斥。
 *
 * <p>与 {@link TradeInitiationResult} 同构：被拒是<b>正常业务结果</b>（用户该看到
 * 「为什么被拒、何时能再来」），不是异常；用自洽校验把「说成功却没邀请」这类矛盾挡在构造期。
 *
 * @param status   结局：{@link Status#CREATED} 或 {@link Status#REJECTED}
 * @param invite   创建成功时的新邀请；被拒时必为 {@code null}
 * @param decision 准入裁决（被拒时携带原因与可重试时刻）
 */
public record TradeInviteCreationResult(Status status, TradeInvite invite,
                                        TradeAdmissionDecision decision) {

    /** 结局。 */
    public enum Status {
        /** 已创建邀请。 */
        CREATED,
        /** 被准入门禁拒绝，未创建邀请。 */
        REJECTED
    }

    public TradeInviteCreationResult {
        if (status == null) {
            throw new EscrowException("邀请创建结果缺少结局状态");
        }
        if (decision == null) {
            throw new EscrowException("邀请创建结果缺少准入裁决");
        }
        if (status == Status.CREATED) {
            if (invite == null) {
                throw new EscrowException("CREATED 结果必须携带邀请——否则等于谎报创建成功");
            }
            if (!decision.allowed()) {
                throw new EscrowException("CREATED 结果的准入裁决必须是放行，实为 " + decision.reason());
            }
        } else {
            if (invite != null) {
                throw new EscrowException("REJECTED 结果不得携带邀请——被拒即未创建");
            }
            if (decision.allowed()) {
                throw new EscrowException("REJECTED 结果的准入裁决必须是拒绝");
            }
        }
    }

    /** 创建成功。 */
    public static TradeInviteCreationResult created(TradeInvite invite) {
        return new TradeInviteCreationResult(Status.CREATED, invite, TradeAdmissionDecision.allow());
    }

    /** 被拒绝（未创建邀请）。 */
    public static TradeInviteCreationResult rejected(TradeAdmissionDecision decision) {
        return new TradeInviteCreationResult(Status.REJECTED, null, decision);
    }
}
