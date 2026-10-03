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
package com.tg.escrow;

import java.math.BigDecimal;

/**
 * 金额展示形态——去尾零（{@code 100.00000000} → {@code 100}），共享单点避免各出口口径不一。
 * 只动展示不动数值：机器消费出口（裁决 API JSON）刻意不用它（schema 保留精确精度）。
 * 用 {@code toPlainString()} 而非 {@code toString()}（后者可能给科学计数法）。
 */
public final class MoneyFormat {

    private MoneyFormat() {
    }

    /** 金额的展示文本；null 原样返回 null。 */
    public static String format(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }
}
