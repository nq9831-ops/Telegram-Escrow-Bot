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
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 重放保护（ET-48）：每签名请求绑定唯一 nonce，有效期内重复即拒。
 *
 * <h2>窗口回收是硬要求（内存防线）</h2>
 * <p>nonce 只在窗口内有意义（请求自身带时效）——记录必须按窗口回收，否则随请求量无界增长
 * （{@code MessageRepeatDetector} 的教训在此固化）。每次 {@link #accept} 顺带清扫过期记录。
 *
 * <p>判定与 {@code SlidingWindowCounter} 同族语义：窗口左闭、过期即出。
 * 非线程安全（单线程签名验证场景；并发侧由调用方串行化）。
 */
public final class ReplayGuard {

    private final Duration window;
    private final Map<String, Instant> seen = new HashMap<>();

    /**
     * @param window nonce 有效窗口（正数）；同一 nonce 在窗口内只接受一次
     */
    public ReplayGuard(Duration window) {
        if (window == null || window.isZero() || window.isNegative()) {
            throw new TggException("重放保护：有效窗口必须为正，实为 " + window);
        }
        this.window = window;
    }

    /**
     * 尝试接受一个 nonce。
     *
     * @param nonce 请求绑定的唯一值（不得空白）
     * @param now   当前时刻
     * @return {@code true} = 首次见到，放行；{@code false} = 窗口内重复，<b>拒绝（重放）</b>
     */
    public boolean accept(String nonce, Instant now) {
        if (nonce == null || nonce.isBlank()) {
            throw new TggException("重放保护：nonce 不得空白");
        }
        if (now == null) {
            throw new TggException("重放保护：当前时刻不可为空");
        }
        sweep(now);
        if (seen.containsKey(nonce)) {
            return false;
        }
        seen.put(nonce, now);
        return true;
    }

    /** 当前驻留的 nonce 数（监控/测试用——验证窗口回收生效）。 */
    public int trackedCount() {
        return seen.size();
    }

    private void sweep(Instant now) {
        Instant cutoff = now.minus(window);
        Iterator<Map.Entry<String, Instant>> it = seen.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().isBefore(cutoff)) {
                it.remove();
            }
        }
    }
}
