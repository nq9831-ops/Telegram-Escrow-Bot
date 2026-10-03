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

/**
 * 新手标识与互刷豁免（ET-38，业务冲突 2.1 的解法）。
 *
 * <h2>为什么前 3 笔豁免互刷检测</h2>
 * <p>信用冷启动的困境：新用户的前几笔交易往往只跟唯一对象发生（无从选择），
 * 若立即套用互刷检测，会被误判为"互刷"而信用冻结——等于惩罚新人。故<b>前 3 笔即便同一对手
 * 也照常计信用</b>，从第 4 笔起互刷检测生效。与 {@code ET-67 互刷不累加信用} 协同：
 * 豁免的是"检测生效时点"，不是"允许互刷"。
 *
 * <p>边界定死：{@code completedCount < 3} 为新手/豁免（"前 3 笔"= 第 1、2、3 笔交易本身）。
 * 纯函数、无状态。
 */
public final class NewcomerBadge {

    /** 新手期笔数上限（完成数低于该值标"新手"）。 */
    public static final int NEWCOMER_THRESHOLD = 3;

    private NewcomerBadge() {
    }

    /**
     * 是否应展示"新手"标识。
     *
     * @param completedCount 已完成交易笔数（不得为负）
     */
    public static boolean isNewcomer(int completedCount) {
        return check(completedCount) < NEWCOMER_THRESHOLD;
    }

    /**
     * 互刷检测是否豁免（前 3 笔不判互刷）。
     *
     * @param completedCount 已完成交易笔数（不得为负）
     */
    public static boolean isMutualBrushExempt(int completedCount) {
        return check(completedCount) < NEWCOMER_THRESHOLD;
    }

    private static int check(int completedCount) {
        if (completedCount < 0) {
            throw new EscrowException("新手判定：完成笔数为负（" + completedCount + "）——数据异常");
        }
        return completedCount;
    }
}
