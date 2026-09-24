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

import com.tg.escrow.common.TggException;

import java.time.Duration;
import java.time.Instant;

/**
 * 定时禁言（GM-02 的到期判定侧）：绑定用户的禁言时段。
 *
 * <p>到期边界<b>闭区间</b>（{@code now >= 起始+时长} 即到期自动解除）——与项目内其它
 * 时限语义一致。解除动作由调度侧触发（调度器属外部依赖，见线上清单）。纯值对象。
 */
public final class MuteSchedule {

    private final long userId;
    private final Instant startedAt;
    private final Duration duration;

    /**
     * @param userId    被禁言用户
     * @param startedAt 禁言起始时刻
     * @param duration  禁言时长（正数）
     */
    public MuteSchedule(long userId, Instant startedAt, Duration duration) {
        if (startedAt == null) {
            throw new TggException("定时禁言：起始时刻未提供");
        }
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new TggException("定时禁言：时长必须为正，实为 " + duration);
        }
        this.userId = userId;
        this.startedAt = startedAt;
        this.duration = duration;
    }

    public long userId() {
        return userId;
    }

    /** 到期时刻。 */
    public Instant expiresAt() {
        return startedAt.plus(duration);
    }

    /** 是否已到期（{@code now >= 到期时刻}，闭区间）——到期即应自动解除。 */
    public boolean isExpired(Instant now) {
        if (now == null) {
            throw new TggException("定时禁言：当前时刻不可为空");
        }
        return !now.isBefore(expiresAt());
    }
}
