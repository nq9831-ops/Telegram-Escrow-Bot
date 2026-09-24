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
 * 并发更新冲突：订单在本次读取之后被他人改过，本次写入被拒绝。
 *
 * <h2>为什么单独一个类型</h2>
 * <p>它和"状态迁移非法"是<b>两种不同的失败</b>，用户该做的事也不同：
 * 状态非法是"你不能这么做"（重试无用），本异常是"你看到的已过期，请重查再决定"
 * （重试有意义）。混用 {@link EscrowException} 会让命令层只能靠比对文案来区分，
 * 一改措辞就静默失效。
 *
 * <p>由持久化适配器在检测到乐观锁冲突时抛出——<b>技术异常（Spring 的
 * {@code ObjectOptimisticLockingFailureException}）不该越过适配器边界</b>流入领域层。
 */
public class ConcurrentOrderUpdateException extends EscrowException {

    public ConcurrentOrderUpdateException(String message) {
        super(message);
    }

    public ConcurrentOrderUpdateException(String message, Throwable cause) {
        super(message, cause);
    }
}
