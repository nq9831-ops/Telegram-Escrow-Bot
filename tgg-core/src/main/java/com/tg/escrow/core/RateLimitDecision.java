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

/**
 * 限流判定结果（原文档 G23）。
 *
 * <p>被拒时<b>必带层级</b>：只说"被限流"而不说哪一层超限，用户与管理员都无法判断该找谁，
 * 也掩盖了配置错误（例如群组阈值被误设成 1）。放行时层级为 {@code null}。
 *
 * @param allowed 是否放行
 * @param layer   被拒时超限的层级；放行时为 {@code null}
 */
public record RateLimitDecision(boolean allowed, Layer layer) {

    /** 限流的三个层级。顺序即检查优先级（用户 → 群组 → 全局）。 */
    public enum Layer {
        /** 用户层。 */
        USER,
        /** 群组层。 */
        GROUP,
        /** 全局层。 */
        GLOBAL
    }

    /** 构造放行结果。 */
    public static RateLimitDecision allow() {
        return new RateLimitDecision(true, null);
    }

    /** 构造拒绝结果；层级不可为空。 */
    public static RateLimitDecision deny(Layer layer) {
        if (layer == null) {
            throw new TggException("限流拒绝必须指名超限层");
        }
        return new RateLimitDecision(false, layer);
    }
}
