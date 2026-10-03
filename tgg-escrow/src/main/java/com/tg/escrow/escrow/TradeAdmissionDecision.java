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

import java.time.Instant;

/**
 * 交易准入裁决——带<b>拒绝原因</b>与<b>可重试时刻</b>，而非裸布尔。
 *
 * <p>为什么不止返回 boolean：用户被拒时必须能回答「为什么」与「什么时候能再来」。
 * 一个只说「不行」的门禁会让用户反复重试并误以为系统故障，把可解释的业务约束
 * 变成不可解释的随机失败。
 *
 * @param allowed    是否放行
 * @param reason     裁决原因
 * @param retryAfter 最早可重试时刻；{@code null} 表示<b>无法预估</b>（争议未决、并发占用中）
 */
public record TradeAdmissionDecision(boolean allowed, Reason reason, Instant retryAfter) {

    /** 裁决原因。三值互斥，且 {@link #ALLOWED} 必与 {@code allowed=true} 同现。 */
    public enum Reason {
        /** 放行。 */
        ALLOWED,
        /** T23：进行中交易已达上限。 */
        CONCURRENT_LIMIT,
        /** T24：冷却期未满。 */
        COOLDOWN,
        /** T25：存在未决争议，冻结新交易。 */
        DISPUTE_HOLD
    }

    public TradeAdmissionDecision {
        if (reason == null) {
            throw new EscrowException("准入裁决缺少原因");
        }
        if (allowed != (reason == Reason.ALLOWED)) {
            throw new EscrowException("准入裁决自相矛盾：allowed=" + allowed + "，reason=" + reason);
        }
        if (allowed && retryAfter != null) {
            throw new EscrowException("放行裁决不应携带 retryAfter");
        }
    }

    /** 放行。 */
    public static TradeAdmissionDecision allow() {
        return new TradeAdmissionDecision(true, Reason.ALLOWED, null);
    }

    /**
     * 拒绝。
     *
     * @param reason     拒绝原因（不得为 {@link Reason#ALLOWED}）
     * @param retryAfter 可重试时刻；无法预估时传 {@code null}
     */
    public static TradeAdmissionDecision reject(Reason reason, Instant retryAfter) {
        return new TradeAdmissionDecision(false, reason, retryAfter);
    }
}
