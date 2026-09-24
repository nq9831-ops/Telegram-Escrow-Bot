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
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * 滑动窗口计数器（原文档 G26）：N 秒内按 key 计数，严格超过阈值即触发。
 *
 * <h2>语义定死（避免边界漂移）</h2>
 * <ul>
 *   <li><b>触发条件 {@code count > threshold}</b>——严格超过，恰好等于阈值不触发。
 *       与 {@link MessageRepeatDetector} 的"达到即算"不同，此处按原文"超阈值"实现。</li>
 *   <li><b>窗口为左闭区间 {@code [now-window, now]}</b>：恰好落在 {@code now-window} 的
 *       记录仍在窗口内。窗口边界是"滑出/保留"最易出错处，故固定为可复现的一侧。</li>
 * </ul>
 *
 * <h2>时钟由调用方注入</h2>
 * <p>{@code now} 逐次传入而非读系统时钟——这样窗口行为可用固定时刻测试，
 * 也保证一次判定内的时刻一致。
 *
 * <p>线程安全未做保证：同一 key 的调用需串行。Bot 的消息派发是单线程，符合此前提。
 */
public final class SlidingWindowCounter {

    private final Duration window;
    private final int threshold;
    private final Map<String, Deque<Instant>> windows = new HashMap<>();

    /**
     * @param window    计数窗口（正数）
     * @param threshold 阈值（≥ 1；计数严格超过它才触发）
     */
    public SlidingWindowCounter(Duration window, int threshold) {
        if (window == null) {
            throw new TggException("滑动窗口：未提供窗口");
        }
        if (window.isZero() || window.isNegative()) {
            throw new TggException("滑动窗口：窗口必须为正，实为 " + window);
        }
        if (threshold < 1) {
            throw new TggException("滑动窗口：阈值必须 ≥ 1，实为 " + threshold);
        }
        this.window = window;
        this.threshold = threshold;
    }

    /**
     * 记录一次事件并判定是否超过阈值。
     *
     * @param key 计数维度（如用户 / 群组 / 全局）
     * @param now 当前时刻
     * @return 记录后窗口内计数是否<b>严格超过</b>阈值
     */
    public boolean recordAndCheck(String key, Instant now) {
        requireValid(key, now);
        Deque<Instant> stamps = windows.computeIfAbsent(key, k -> new ArrayDeque<>());
        evict(stamps, now);
        stamps.addLast(now);
        return stamps.size() > threshold;
    }

    /**
     * 查询窗口内当前计数（不记录）。
     *
     * @param key 计数维度
     * @param now 当前时刻
     * @return 窗口内计数（未出现过返回 0）
     */
    public int currentCount(String key, Instant now) {
        requireValid(key, now);
        Deque<Instant> stamps = windows.get(key);
        if (stamps == null) {
            return 0;
        }
        evict(stamps, now);
        return stamps.size();
    }

    private void evict(Deque<Instant> stamps, Instant now) {
        Instant cutoff = now.minus(window);
        while (!stamps.isEmpty() && stamps.peekFirst().isBefore(cutoff)) {
            stamps.pollFirst();
        }
    }

    private static void requireValid(String key, Instant now) {
        if (key == null || key.isBlank()) {
            throw new TggException("滑动窗口：key 不可为空");
        }
        if (now == null) {
            throw new TggException("滑动窗口：未提供当前时刻");
        }
    }
}
