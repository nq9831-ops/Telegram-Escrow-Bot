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
import com.tg.escrow.core.RateLimitDecision.Layer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 三级限流（G23）的行为固定测试。
 *
 * <p>本类的验收重点是：<b>被拒时必须指名是哪一层超限</b>。只说"被限流"而不说层级，
 * 会让用户与管理员都无法判断该找谁——也掩盖了配置错误（例如群组阈值被误设成 1）。
 */
class RateLimiterTest {

    private static final Duration WINDOW = Duration.ofSeconds(10);
    private static final Instant T0 = Instant.parse("2026-09-23T00:00:00Z");

    private static RateLimitPolicy policy(int user, int group, int global) {
        return new RateLimitPolicy(WINDOW, user, group, global);
    }

    @Test
    @DisplayName("未超任何阈值时放行")
    void allowsUnderLimits() {
        RateLimiter r = new RateLimiter(policy(5, 10, 100));

        assertThat(r.check(1L, 100L, T0).allowed()).isTrue();
    }

    @Test
    @DisplayName("用户层超限 → 指名 USER")
    void userLayerDenied() {
        RateLimiter r = new RateLimiter(policy(2, 10, 100));

        r.check(1L, 100L, T0); // user1: 1
        r.check(1L, 100L, T0); // user1: 2
        RateLimitDecision d = r.check(1L, 100L, T0); // user1: 3 > 2

        assertThat(d.allowed()).isFalse();
        assertThat(d.layer()).isEqualTo(Layer.USER);
    }

    @Test
    @DisplayName("群组层超限 → 指名 GROUP（群内多用户累计）")
    void groupLayerDenied() {
        RateLimiter r = new RateLimiter(policy(100, 3, 100));

        r.check(1L, 100L, T0); // group100: 1
        r.check(2L, 100L, T0); // group100: 2
        r.check(3L, 100L, T0); // group100: 3
        RateLimitDecision d = r.check(4L, 100L, T0); // group100: 4 > 3，user4 首次

        assertThat(d.layer()).isEqualTo(Layer.GROUP);
    }

    @Test
    @DisplayName("全局层超限 → 指名 GLOBAL")
    void globalLayerDenied() {
        RateLimiter r = new RateLimiter(policy(100, 100, 2));

        r.check(1L, 100L, T0); // global: 1
        r.check(2L, 200L, T0); // global: 2
        RateLimitDecision d = r.check(3L, 300L, T0); // global: 3 > 2

        assertThat(d.layer()).isEqualTo(Layer.GLOBAL);
    }

    @Test
    @DisplayName("多层同时超限时，返回最先检查的 USER 层（层级顺序确定，结果可复现）")
    void userLayerTakesPrecedence() {
        RateLimiter r = new RateLimiter(policy(1, 1, 1));

        r.check(1L, 100L, T0); // user1:1 group100:1 global:1
        RateLimitDecision d = r.check(1L, 100L, T0); // 三层均 2 > 1

        assertThat(d.layer()).isEqualTo(Layer.USER);
    }

    @Test
    @DisplayName("阈值 < 1 在构造期拒绝——阈值为 0 会永久拒绝一切请求")
    void invalidPolicyRejected() {
        assertThatThrownBy(() -> new RateLimitPolicy(WINDOW, 0, 1, 1))
                .isInstanceOf(TggException.class);
        assertThatThrownBy(() -> new RateLimitPolicy(WINDOW, 1, 1, 0))
                .isInstanceOf(TggException.class);
    }

    @Test
    @DisplayName("被 USER 层拒的请求不得推高群组配额——否则一个滥用者可让整群停摆")
    void userDeniedRequestDoesNotConsumeGroupQuota() {
        // user 阈值 1（第 2 次即 USER 拒），group 阈值 10，global 高
        RateLimiter r = new RateLimiter(policy(1, 10, 1000));

        r.check(1L, 100L, T0); // user1 放行；group100 计数 = 1
        r.check(1L, 100L, T0); // user1 超限 → USER 拒；这第 2 次不得计入 group100

        // 若滥用者的被拒请求被计入群组（group100=2），这 9 个正常用户会把 group100
        // 顶到 11 > 10，第 9 个起被 GROUP 连带拒绝——正是要防的"一人拖垮全群"
        for (int i = 0; i < 9; i++) {
            assertThat(r.check(2L + i, 100L, T0).allowed())
                    .as("正常用户 %d 不应被他人滥用连带拒绝", 2L + i)
                    .isTrue();
        }
    }
}
