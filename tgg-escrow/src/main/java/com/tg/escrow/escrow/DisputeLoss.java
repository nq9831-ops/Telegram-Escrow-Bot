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

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 争议败诉记录（表 {@code dispute_losses}）——ET-61「多维度惩罚」缺失的那块数据源。
 *
 * <h2>为什么需要它</h2>
 * <p>{@link PenaltyPlanner} 的 {@code disputeLosses} 入参此前<b>无处可取</b>：裁决只改订单状态，
 * 不记「谁败了」，于是「败诉 ≥ 2 → 投票暂停」这一维永远算不出来。
 *
 * <h2>败诉方口径（用户 2026-10-02 拍板）</h2>
 * <p>裁决 {@code outcome} <b>对其不利</b>的一方即败诉方：{@code RELEASE}（放款卖方）→ 买方败；
 * {@code REFUND}（退款买方）→ 卖方败。该口径在 {@link PenaltyService#loserOf} 单点定义。
 *
 * <h2>主键即订单号</h2>
 * <p>一单一败方 —— 重复裁决不会重复记罚（幂等由主键保证，而非靠调用方小心）。
 */
@Entity
@Table(name = "dispute_losses")
public class DisputeLoss {

    @Id
    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "loser_user_id", nullable = false)
    private long loserUserId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    /** JPA 要求的无参构造。业务代码请用下面的公开构造器。 */
    protected DisputeLoss() {
    }

    public DisputeLoss(long orderId, long loserUserId, Instant occurredAt) {
        if (occurredAt == null) {
            throw new EscrowException("败诉记录：发生时刻不可为空");
        }
        this.orderId = orderId;
        this.loserUserId = loserUserId;
        this.occurredAt = occurredAt;
    }

    public Long getOrderId() {
        return orderId;
    }

    public long getLoserUserId() {
        return loserUserId;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
