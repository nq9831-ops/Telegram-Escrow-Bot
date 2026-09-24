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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * 交易维护期服务（Wave 4 接线）——把 {@link MaintenanceWindow} 与 {@link TradeTimeoutPolicy}
 * 接成一条可查的路径：<b>交付之后买方还有多久验收</b>。
 *
 * <h2>语义（本波定死）</h2>
 * <p>维护期 = {@code DELIVERED} 之后、买方验收（或超时自动放款）的窗口；起算于订单的
 * {@code updatedAt}（卖方交付那一刻），时长取 {@link MaintenanceWindow#defaultDuration()}。
 *
 * <h2>诚实边界：只判定、不执行</h2>
 * <p>本项目<b>没有调度器</b>，因此超时<b>不会</b>自动触发放款/退款——本服务只回答
 * 「此刻是否已过维护期」，由调用方（状态回执）如实转述。等引入调度器后，把
 * {@link #statusOf} 的结果接到定时任务上即可，判定逻辑不必改。
 *
 * <p>只对 {@code DELIVERED} 适用；其余状态返回 {@link MaintenanceStatus#notApplicable()}。
 */
public final class TradeMaintenanceService {

    /**
     * 维护期状态。
     *
     * @param applicable 该状态是否有维护期（仅 {@code DELIVERED} 为真）
     * @param expired    是否已过维护期
     * @param remaining  剩余时长；已过期或不适用的恒为 {@link Duration#ZERO}
     */
    public record MaintenanceStatus(boolean applicable, boolean expired, Duration remaining) {

        static MaintenanceStatus notApplicable() {
            return new MaintenanceStatus(false, false, Duration.ZERO);
        }
    }

    private final MaintenanceWindow window;
    private final TradeTimeoutPolicy timeoutPolicy;
    private final Clock clock;

    public TradeMaintenanceService(MaintenanceWindow window, TradeTimeoutPolicy timeoutPolicy, Clock clock) {
        if (window == null) {
            throw new EscrowException("维护期服务：未提供维护期窗口");
        }
        if (timeoutPolicy == null) {
            throw new EscrowException("维护期服务：未提供超时策略");
        }
        if (clock == null) {
            throw new EscrowException("维护期服务：未提供时钟");
        }
        this.window = window;
        this.timeoutPolicy = timeoutPolicy;
        this.clock = clock;
    }

    /** 维护期时长选项数量（供界面呈现）。 */
    public int optionCount() {
        return window.optionCount();
    }

    /**
     * 查某订单此刻的维护期状态。
     *
     * @param order 订单（不得为 {@code null}）
     * @return 维护期状态；非 {@code DELIVERED} 时为「不适用」
     */
    public MaintenanceStatus statusOf(EscrowOrder order) {
        if (order == null) {
            throw new EscrowException("维护期服务：订单不可为空");
        }
        if (order.currentState() != EscrowOrder.State.DELIVERED) {
            return MaintenanceStatus.notApplicable();
        }
        Instant now = clock.instant();
        Instant deadline = window.deadline(order.getUpdatedAt(), window.defaultDuration());
        // 过期判定走超时策略（与自动放款的规则同源，避免两处各算一套）
        boolean expired = timeoutPolicy.check(order.currentState(), order.getUpdatedAt(), now).timedOut();
        return new MaintenanceStatus(true, expired, expired ? Duration.ZERO : Duration.between(now, deadline));
    }
}
