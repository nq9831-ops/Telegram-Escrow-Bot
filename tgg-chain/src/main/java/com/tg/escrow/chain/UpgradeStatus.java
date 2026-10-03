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
package com.tg.escrow.chain;

import java.math.BigInteger;

/**
 * 链上升级提案状态（合约 {@code get fun upgradeStatus(): (int, int)} 的读模型，⑥）。
 *
 * <p>无提案时合约返回 {@code (0, 0)}——与 {@link #none()} 对应。{@code codeHash} 是
 * <b>提案的新代码 hash</b>（提案留痕记 hash 不进整码），{@code proposedAt} 是提案时刻，
 * 冷静期从它起算（合约内判据 {@code now >= proposedAt + 72h}）。
 *
 * @param codeHash   提案的新代码 cell hash（无提案 = 0）
 * @param proposedAt 提案时刻（unix 秒，无提案 = 0）
 */
public record UpgradeStatus(BigInteger codeHash, long proposedAt) {

    public UpgradeStatus {
        if (codeHash == null) {
            throw new IllegalArgumentException("codeHash 不可为空（无提案用 none()）");
        }
    }

    /** 无提案状态（合约返回 (0, 0)）。 */
    public static UpgradeStatus none() {
        return new UpgradeStatus(BigInteger.ZERO, 0L);
    }

    /** 是否存在待执行提案。 */
    public boolean hasProposal() {
        return codeHash.signum() != 0;
    }

    /**
     * 冷静期是否已满（提案时刻 + 冷静期 ≤ 现在）。
     *
     * <p>链下判据与合约内判据同口径（{@code >=}）；<b>合约内校验才是权威</b>，
     * 本方法只用于链下编排决定「什么时候可以发 Apply」。
     *
     * @param nowSeconds           当前时刻（unix 秒）
     * @param coolDownSeconds      冷静期秒数（72h = 259200）
     * @return 无提案时恒 {@code false}；有提案时按判据计算
     */
    public boolean coolDownElapsed(long nowSeconds, long coolDownSeconds) {
        return hasProposal() && nowSeconds >= proposedAt + coolDownSeconds;
    }
}
