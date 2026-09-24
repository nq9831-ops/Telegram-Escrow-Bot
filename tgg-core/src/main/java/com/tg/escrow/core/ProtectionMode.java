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
 * 保护模式（原文档 G27）：临时拒绝新入群申请（通常由滑动窗口检测触发）。
 *
 * <h2>开启必须说明原因</h2>
 * <p>{@link #enable} 要求非空原因。无原因的"保护模式"会让管理员只看到"申请被拒"
 * 却不知为何，无法判断是误触发还是真实攻击。原因随状态一起被 {@link #reason()} 读出，
 * 供回执与排查使用。
 *
 * <p>{@link #disable} 清空原因——关闭后残留旧原因会误导下一次排查。
 *
 * <p>非线程安全；单线程翻转即可。
 */
public final class ProtectionMode {

    private boolean enabled;
    private String reason;

    /** 是否处于保护模式。 */
    public boolean isEnabled() {
        return enabled;
    }

    /** 开启原因；未开启时为 {@code null}。 */
    public String reason() {
        return reason;
    }

    /** 是否应拒绝新入群申请（当前等于 {@link #isEnabled}）。 */
    public boolean shouldRejectJoin() {
        return enabled;
    }

    /**
     * 开启保护模式（幂等：重复开启更新原因）。
     *
     * @param reason 开启原因（不得空白）
     */
    public void enable(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new TggException("保护模式：开启必须说明原因");
        }
        this.enabled = true;
        this.reason = reason;
    }

    /** 关闭保护模式，并清空原因。 */
    public void disable() {
        this.enabled = false;
        this.reason = null;
    }
}
