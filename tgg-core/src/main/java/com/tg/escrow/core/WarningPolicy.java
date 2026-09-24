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
 * 警告超阈值处罚（GM-03 的处罚判定侧）：累计警告到阈值自动触发处罚。
 *
 * <p>阈值语义定死为<b>达到即触发</b>（{@code count >= threshold}，与 MessageRepeatDetector 一致）。
 * 处罚分级：达到 {@code muteThreshold} → 禁言；达到 {@code kickThreshold} → 踢出（重罚）。
 * 构造期校验阈值单调（{@code 1 ≤ mute ≤ kick}）——倒置配置会让重罚先于轻罚，语义不可解释。
 * 纯函数、无状态。
 */
public final class WarningPolicy {

    /** 处罚动作。 */
    public enum Action {
        /** 继续观察。 */
        NONE,
        /** 禁言（轻罚）。 */
        MUTE,
        /** 踢出（重罚）。 */
        KICK
    }

    private final int muteThreshold;
    private final int kickThreshold;

    /**
     * @param muteThreshold 禁言阈值（≥1，含本数）
     * @param kickThreshold 踢出阈值（≥ 禁言阈值，含本数）
     */
    public WarningPolicy(int muteThreshold, int kickThreshold) {
        if (muteThreshold < 1) {
            throw new TggException("警告处罚：禁言阈值必须 ≥ 1，实为 " + muteThreshold);
        }
        if (kickThreshold < muteThreshold) {
            throw new TggException("警告处罚：阈值倒置（踢出 " + kickThreshold
                    + " < 禁言 " + muteThreshold + "）——重罚不得先于轻罚");
        }
        this.muteThreshold = muteThreshold;
        this.kickThreshold = kickThreshold;
    }

    /** 按累计警告数判定处罚（达到即触发）。 */
    public Action actionFor(int warnCount) {
        if (warnCount < 0) {
            throw new TggException("警告处罚：累计数不得为负，实为 " + warnCount);
        }
        if (warnCount >= kickThreshold) {
            return Action.KICK;
        }
        if (warnCount >= muteThreshold) {
            return Action.MUTE;
        }
        return Action.NONE;
    }
}
