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

import java.math.BigDecimal;

/**
 * 交易金额分层验证（ET-33）：按金额决定交易前的验证强度。
 *
 * <h2>边界归属定死</h2>
 * <p>核心规则「≤100 正常；100-1000 二次确认；≥1000 额外验证」的区间写法在 100/1000 处重叠。
 * 此处定死：<b>100 归 NORMAL（含本数）、1000 归 EXTRA（含本数，高风险侧从严）</b>——
 * 重叠处取严不取宽，宁可多问一次，不可少验一次。
 *
 * <p>阈值由部署者注入（默认值 100/1000 是业务参数，见待决 B 组）。纯函数、无状态。
 */
public final class AmountTierPolicy {

    /** 验证强度分档。 */
    public enum Tier {
        /** 正常放行。 */
        NORMAL,
        /** 二次确认。 */
        CONFIRM,
        /** 额外验证。 */
        EXTRA
    }

    private final BigDecimal normalMax;
    private final BigDecimal confirmMax;

    /**
     * @param normalMax NORMAL 档上限（正数，含本数）
     * @param confirmMax CONFIRM 档上限（正数，含本数归 EXTRA）
     */
    public AmountTierPolicy(BigDecimal normalMax, BigDecimal confirmMax) {
        if (normalMax == null || confirmMax == null
                || normalMax.signum() <= 0 || confirmMax.signum() <= 0) {
            throw new EscrowException("金额分层：阈值必须为正数");
        }
        if (normalMax.compareTo(confirmMax) >= 0) {
            throw new EscrowException("金额分层：阈值倒置（NORMAL 上限 " + normalMax
                    + " ≥ EXTRA 下限 " + confirmMax + "）");
        }
        this.normalMax = normalMax;
        this.confirmMax = confirmMax;
    }

    /**
     * 判定金额所属分档。
     *
     * @param amount 交易金额（必须为正）
     * @return {@code ≤normalMax} NORMAL；{@code <confirmMax} CONFIRM；其余 EXTRA
     */
    public Tier tierOf(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new EscrowException("金额分层：金额必须为正数，实为 " + amount);
        }
        if (amount.compareTo(normalMax) <= 0) {
            return Tier.NORMAL;
        }
        if (amount.compareTo(confirmMax) < 0) {
            return Tier.CONFIRM;
        }
        return Tier.EXTRA;
    }
}
