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

/**
 * 三级限流策略（原文档 G23）：用户 / 群组 / 全局三层各自的窗口与阈值。
 *
 * <p>数值全部由部署者给定——代码不内置业务默认值。构造期校验把"阈值写成 0"
 * 这种会令限流器永久拒绝一切请求的配置拦在启动前。
 *
 * @param window          三层共用的计数窗口（正数）
 * @param userThreshold   单用户阈值（≥ 1）
 * @param groupThreshold  单群组阈值（≥ 1）
 * @param globalThreshold 全局阈值（≥ 1）
 */
public record RateLimitPolicy(Duration window, int userThreshold,
                              int groupThreshold, int globalThreshold) {

    public RateLimitPolicy {
        if (window == null) {
            throw new TggException("限流策略：未提供窗口");
        }
        if (window.isZero() || window.isNegative()) {
            throw new TggException("限流策略：窗口必须为正，实为 " + window);
        }
        requirePositive(userThreshold, "用户层");
        requirePositive(groupThreshold, "群组层");
        requirePositive(globalThreshold, "全局层");
    }

    private static void requirePositive(int value, String layer) {
        if (value < 1) {
            throw new TggException("限流策略：" + layer + "阈值必须 ≥ 1，实为 " + value
                    + "——阈值为 0 会永久拒绝一切请求");
        }
    }
}
