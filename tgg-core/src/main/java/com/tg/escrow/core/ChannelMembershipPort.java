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

/**
 * 频道订阅查询端口（GM-16 / T60 的前置）：某人是否订阅了某频道。
 *
 * <h2>为什么必须是三态，而不是 boolean</h2>
 * <p>把它做成 {@code boolean} 会逼实现把「查不到」（网络失败、权限不足、频道不存在）归到某一边，
 * 两种归法都有害：
 * <ul>
 *   <li>归 {@code false} → 一次网络抖动就会把<b>已订阅的用户误踢</b>；</li>
 *   <li>归 {@code true} → 门禁形同虚设。</li>
 * </ul>
 * 故返回 {@link Membership}：只有明确查到未订阅才可处罚，查不清就<b>不处罚也不放行</b>
 * （与项目「无法确认就不做破坏性动作」的一贯方向一致——参 {@code ChatKind.moderatable()}）。
 *
 * <h2>实现契约</h2>
 * <p>任何异常、{@code null}、未知状态字符串，一律返回 {@link Membership#UNKNOWN}，
 * <b>绝不</b>退化成 {@link Membership#NOT_SUBSCRIBED}。
 */
@FunctionalInterface
public interface ChannelMembershipPort {

    /** 订阅状态三态。 */
    enum Membership {
        /** 明确已订阅。 */
        SUBSCRIBED,
        /** 明确未订阅（可以据此处置）。 */
        NOT_SUBSCRIBED,
        /** 查不清（失败/未知）——不得据此处置。 */
        UNKNOWN
    }

    /**
     * 查某用户在某频道的订阅状态。
     *
     * @param channel 频道标识（{@code @name} 或等价形式）
     * @param userId  用户 ID
     * @return 三态之一；不确定一律 {@link Membership#UNKNOWN}
     */
    Membership membershipOf(String channel, long userId);
}
