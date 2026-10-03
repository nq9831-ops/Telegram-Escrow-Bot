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

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 维护期窗口（原文档 T26）：放款后"还能申请售后"的时间窗。
 *
 * <h2>恰好 5 个选项</h2>
 * <p>选项数量定死为 {@value #OPTION_COUNT}——用户界面按 1–{@value #OPTION_COUNT} 呈现，
 * 数量漂移会让文案与下标错位。默认项可配置（{@code defaultIndex}），但必须落在合法区间。
 *
 * <h2>到期边界为闭区间</h2>
 * <p>{@code now >= deadline} 即到期，与超时策略的边界语义保持一致。
 */
public final class MaintenanceWindow {

    /** 维护期时长选项的固定数量。 */
    public static final int OPTION_COUNT = 5;

    private final List<Duration> options;
    private final int defaultIndex;

    /**
     * @param options      恰好 {@value #OPTION_COUNT} 个正时长
     * @param defaultIndex 默认项下标（0 起）
     */
    public MaintenanceWindow(List<Duration> options, int defaultIndex) {
        if (options == null || options.size() != OPTION_COUNT) {
            throw new EscrowException("维护期：必须提供恰好 " + OPTION_COUNT + " 个时长选项");
        }
        for (Duration option : options) {
            if (option == null || option.isZero() || option.isNegative()) {
                throw new EscrowException("维护期：时长选项必须为正，实为 " + option);
            }
        }
        if (defaultIndex < 0 || defaultIndex >= OPTION_COUNT) {
            throw new EscrowException("维护期：默认项下标必须在 0–" + (OPTION_COUNT - 1)
                    + "，实为 " + defaultIndex);
        }
        this.options = List.copyOf(options);
        this.defaultIndex = defaultIndex;
    }

    /** 选项数量。 */
    public int optionCount() {
        return options.size();
    }

    /** 默认时长。 */
    public Duration defaultDuration() {
        return options.get(defaultIndex);
    }

    /**
     * 按 1 起的选项编号取时长。
     *
     * @param oneBasedChoice 1–{@value #OPTION_COUNT}
     * @throws EscrowException 编号越界
     */
    public Duration durationAt(int oneBasedChoice) {
        if (oneBasedChoice < 1 || oneBasedChoice > OPTION_COUNT) {
            throw new EscrowException("维护期：选项编号必须在 1–" + OPTION_COUNT
                    + "，实为 " + oneBasedChoice);
        }
        return options.get(oneBasedChoice - 1);
    }

    /** 给定起始时刻与时长期限，算维护期截止时刻。 */
    public Instant deadline(Instant startedAt, Duration chosen) {
        if (startedAt == null || chosen == null) {
            throw new EscrowException("维护期：时刻与时长不可为空");
        }
        return startedAt.plus(chosen);
    }

    /** 是否已到期（{@code now >= deadline}，闭区间）。 */
    public boolean isExpired(Instant startedAt, Duration chosen, Instant now) {
        if (now == null) {
            throw new EscrowException("维护期：当前时刻不可为空");
        }
        return !now.isBefore(deadline(startedAt, chosen));
    }
}
