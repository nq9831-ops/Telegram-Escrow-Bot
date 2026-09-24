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

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * 静默时段（原文档 G19）：判定某时刻是否落在配置的静默窗口内。
 *
 * <h2>跨午夜是核心难点</h2>
 * <p>{@code 22:00–02:00} 不是"22 到 2 的时钟数"（那样为空区间），而是
 * {22:00–24:00} ∪ {00:00–02:00}。判定：
 * <ul>
 *   <li>普通（{@code start < end}）：{@code start <= t < end}</li>
 *   <li>跨午夜（{@code start > end}）：{@code t >= start || t < end}</li>
 * </ul>
 *
 * <p>边界定死为<b>含起点、不含终点</b>。时区由构造注入——用 UTC 会让"22:00 静默"
 * 在不同部署地表现为不同当地时间。
 *
 * <p>{@code start == end} 被拒绝：它既可解释为"全天静默"又可解释为"零长度"，语义歧义。
 */
public final class SilenceWindow {

    private final LocalTime start;
    private final LocalTime end;
    private final ZoneId zone;

    /**
     * @param start 静默开始（含）
     * @param end   静默结束（不含）
     * @param zone  判定所用时区
     */
    public SilenceWindow(LocalTime start, LocalTime end, ZoneId zone) {
        if (start == null || end == null) {
            throw new TggException("静默时段：起止时间不可为空");
        }
        if (zone == null) {
            throw new TggException("静默时段：时区不可为空");
        }
        if (start.equals(end)) {
            throw new TggException("静默时段：起止时刻相同——无法区分「全天静默」与「零长度」，拒绝");
        }
        this.start = start;
        this.end = end;
        this.zone = zone;
    }

    /**
     * 该时刻是否处于静默窗口内。
     *
     * @param now 当前时刻
     * @return 落在窗口内为 {@code true}
     */
    public boolean isSilenced(Instant now) {
        if (now == null) {
            throw new TggException("静默时段：当前时刻不可为空");
        }
        LocalTime t = now.atZone(zone).toLocalTime();
        if (start.isBefore(end)) {
            return !t.isBefore(start) && t.isBefore(end);
        }
        // 跨午夜
        return !t.isBefore(start) || t.isBefore(end);
    }
}
