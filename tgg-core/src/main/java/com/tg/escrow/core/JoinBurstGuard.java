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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * 入群爆发自动保护：把 {@link ProtectionMode} 文档里那句「通常由滑动窗口检测触发」
 * <b>从承诺变成实现</b>。
 *
 * <h2>为什么必须有它</h2>
 * <p>在此之前 {@link ProtectionMode#enable} 的唯一生产调用点是启动时读配置——也就是说
 * 「保护模式」这个能力在运行期<b>无法被触发</b>，类文档承诺的自动触发并不存在。
 * 于是"保护模式会拦截入群"这条链路虽有实现却不可达。
 *
 * <h2>惰性解除：本项目没有调度器，也不打算有</h2>
 * <p>触发后若没人解除，一次误触发就会让群<b>永久</b>拒绝新成员。解除判定因此放在每次入群的
 * 调用里（惰性）：冷却期内不解除（否则攻击者只要慢下来就能立刻恢复），冷却期满且未再爆发
 * 才解除。这与项目既有的"惰性过期"取向一致（参邀请有效期的判定方式）。
 *
 * <h2>为什么不按群隔离</h2>
 * <p>{@link ProtectionMode} 本身是<b>全局</b>单例（不分群），计数维度随之取全局——
 * 若这里按群计数、却去开关一个全局开关，两个语义会打架。真要按群隔离，得先让
 * {@code ProtectionMode} 按群，那是另一件事。
 *
 * <h2>默认不改行为</h2>
 * <p>上限 ≤ 0（未配置）时<b>完全不检测</b>、绝不改动保护模式——不配置就什么都不发生。
 */
public final class JoinBurstGuard {

    private static final String KEY = "join";

    private final ProtectionMode mode;
    private final Duration cooldown;
    private final Clock clock;
    private final int maxJoins;
    /** {@code null} = 未配置上限，不检测。 */
    private final SlidingWindowCounter counter;
    /** 上一次爆发时刻；{@code null} 表示从未爆发。 */
    private Instant lastBurstAt;

    /**
     * @param window   计数窗口（未配置上限时可为 {@code null}）
     * @param maxJoins 窗口内允许的最大入群数；<b>≤ 0 表示未配置、不检测</b>
     * @param cooldown 爆发后的保护冷却期（正数）
     * @param mode     保护模式（全局单例）
     * @param clock    时钟（注入使冷却判定可测）
     */
    public JoinBurstGuard(Duration window, int maxJoins, Duration cooldown,
                          ProtectionMode mode, Clock clock) {
        if (mode == null) {
            throw new TggException("入群爆发保护：保护模式不可为空");
        }
        if (clock == null) {
            throw new TggException("入群爆发保护：时钟不可为空");
        }
        if (cooldown == null || cooldown.isZero() || cooldown.isNegative()) {
            throw new TggException("入群爆发保护：冷却期必须为正，实为 " + cooldown);
        }
        if (maxJoins > 0 && window == null) {
            throw new TggException("入群爆发保护：配置了上限就必须提供计数窗口");
        }
        this.mode = mode;
        this.cooldown = cooldown;
        this.clock = clock;
        this.maxJoins = maxJoins;
        this.counter = maxJoins > 0 ? new SlidingWindowCounter(window, maxJoins) : null;
    }

    /**
     * 未启用的实例：不检测、不改行为。
     *
     * <p>给"未配置上限"的装配与测试用——避免每处都手写一串与业务无关的时长参数。
     */
    public static JoinBurstGuard disabled(ProtectionMode mode, Clock clock) {
        return new JoinBurstGuard(Duration.ofMinutes(1), 0, Duration.ofMinutes(1), mode, clock);
    }

    /**
     * 记一次入群：超过上限即开启保护；否则在冷却期满后惰性解除。
     *
     * <p>未配置上限时本方法<b>什么都不做</b>。
     */
    public void onJoin() {
        if (counter == null) {
            return;
        }
        java.time.Instant now = clock.instant();
        if (counter.recordAndCheck(KEY, now)) {
            lastBurstAt = now;
            mode.enable("窗口内入群数超过 " + maxJoins + " 次，疑似批量拉人");
            return;
        }
        if (mode.shouldRejectJoin() && lastBurstAt != null
                && !now.isBefore(lastBurstAt.plus(cooldown))) {
            mode.disable();
        }
    }
}
