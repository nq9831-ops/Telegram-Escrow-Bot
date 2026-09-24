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
 * 通知三维度策略（GM-36，SPEC 6.6）：可见范围 × 是否推送 × 是否留存。
 *
 * <h2>三个维度互相独立，不得混淆</h2>
 * <ul>
 *   <li><b>可见范围</b>：{@code PERMANENT}（群内所有人可见）/ {@code EPHEMERAL}（仅单人可见，
 *       Telegram Bot API 10.2+ 的临时消息）；</li>
 *   <li><b>是否推送</b>：{@code silent}（{@code disable_notification}——消息照常可见、可留存，
 *       只是不响铃）；</li>
 *   <li><b>是否留存</b>：是否作为可追溯证据保留。</li>
 * </ul>
 *
 * <h2>两条不变量（构造期强制）</h2>
 * <ol>
 *   <li><b>临时消息不可留存</b>——仅单人可见却"留存"语义自相矛盾；</li>
 *   <li>关键节点恒为永久 + 留存——由 {@link #critical()} 保证（证据保全，08 的 2.4）。</li>
 * </ol>
 *
 * @param ephemerality 可见范围
 * @param silent       是否静默（不推送响铃）
 * @param retained     是否留存
 */
public record NoticePolicy(Ephemerality ephemerality, boolean silent, boolean retained) {

    /** 可见范围。 */
    public enum Ephemerality {
        /** 群内永久消息。 */
        PERMANENT,
        /** 临时消息（仅单个成员可见）。 */
        EPHEMERAL
    }

    public NoticePolicy {
        if (ephemerality == null) {
            throw new TggException("通知策略：可见范围不可为空");
        }
        if (ephemerality == Ephemerality.EPHEMERAL && retained) {
            throw new TggException("通知策略：临时消息不可留存（仅单人可见与留存语义矛盾）");
        }
    }

    /** 关键节点（资金锁定/确认收货/争议发起）：永久 + 推送 + 留存。 */
    public static NoticePolicy critical() {
        return new NoticePolicy(Ephemerality.PERMANENT, false, true);
    }

    /** 中间进度（处理中/已锁定资金等）：永久但静默——静默 ≠ 临时，消息可见、可留存。 */
    public static NoticePolicy progress() {
        return new NoticePolicy(Ephemerality.PERMANENT, true, true);
    }

    /** 隐私提示（维护期倒计时等）：临时（仅单人可见）且不留存。 */
    public static NoticePolicy privacyHint() {
        return new NoticePolicy(Ephemerality.EPHEMERAL, true, false);
    }
}
