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

/**
 * 三级限流器（原文档 G23）：用户 / 群组 / 全局三层各自独立阈值，任一层超限即拒绝。
 *
 * <h2>计数语义：命中即短路</h2>
 * <p>{@link #check} 按 {@code 用户 → 群组 → 全局} 的顺序逐层判定，<b>任一层超限即返回</b>，
 * 不再推进更外层计数。理由：三层阈值互相独立，若被上层拒的请求仍计入下层，一个滥用者
 * 在打满自己配额的同时会把群组配额一起推高，导致<b>同群正常用户被连带拒绝</b>——
 * 即一人可让整群停摆。短路使滥用者只消耗自己的配额。
 *
 * <p>多层同时超限时返回优先级最高（最先检查）的 USER 层——顺序固定，结果可复现。
 * 复用 {@link SlidingWindowCounter}：三层的窗口与阈值来自 {@link RateLimitPolicy}。
 */
public final class RateLimiter {

    private static final String GLOBAL_KEY = "global";

    private final SlidingWindowCounter users;
    private final SlidingWindowCounter groups;
    private final SlidingWindowCounter global;

    public RateLimiter(RateLimitPolicy policy) {
        if (policy == null) {
            throw new TggException("限流：未提供策略");
        }
        this.users = new SlidingWindowCounter(policy.window(), policy.userThreshold());
        this.groups = new SlidingWindowCounter(policy.window(), policy.groupThreshold());
        this.global = new SlidingWindowCounter(policy.window(), policy.globalThreshold());
    }

    /**
     * 记录一次请求并判定是否放行。
     *
     * @param userId  发起用户
     * @param groupId 所在群组
     * @param now     当前时刻
     * @return 放行，或被拒并指名超限层级
     */
    public RateLimitDecision check(long userId, long groupId, Instant now) {
        if (now == null) {
            throw new TggException("限流：未提供当前时刻");
        }

        if (users.recordAndCheck("u:" + userId, now)) {
            return RateLimitDecision.deny(RateLimitDecision.Layer.USER);
        }
        if (groups.recordAndCheck("g:" + groupId, now)) {
            return RateLimitDecision.deny(RateLimitDecision.Layer.GROUP);
        }
        if (global.recordAndCheck(GLOBAL_KEY, now)) {
            return RateLimitDecision.deny(RateLimitDecision.Layer.GLOBAL);
        }
        return RateLimitDecision.allow();
    }
}
