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
 * 争议会话（表 {@code escrow_disputes}）——让 {@link DisputeStatementFlow} 的内存状态
 * 在重启后不丢，并承载证据窗口（ET-45）的起点与截止。
 *
 * <h2>为什么单独一张表，而不是给 {@code escrow_orders} 加列</h2>
 * <p>双方陈述、各自已读、证据窗口是「争议过程账」，与订单的状态/金额账不同职责；
 * 且一行订单只可能有一份争议会话（主键即订单号），单列一张表让争议侧的读写与订单账本解耦。
 *
 * <h2>状态机为何不落在本类</h2>
 * <p>「每方限一条 + 互认已读才 complete」的规则<b>只</b>在 {@link DisputeStatementFlow} 定义
 * （两处规则必漂移）。本类只做<b>持久化形状</b>：存陈述文本与两个已读时刻，并提供与状态机之间的
 * 双向转换（{@link #toFlow} / {@link #applyFlow}）——与 {@code TradeGroup} 对
 * {@code TradeGroupLifecycle} 的取向一致。
 *
 * <h2>表结构由 Flyway 管理</h2>
 * <p>{@code ddl-auto=validate}——实体不许自行建表或改表，结构漂移在启动期即失败。
 */
@Entity
@Table(name = "escrow_disputes")
public class DisputeSession {

    @Id
    @Column(name = "order_id", nullable = false)
    private Long orderId;

    /** 争议发起时刻——证据窗口的起点（ET-45）。 */
    @Column(name = "opened_at", nullable = false)
    private Instant openedAt;

    /** 证据提交截止（= {@code opened_at + 窗口}）；不随维护期暂停。 */
    @Column(name = "evidence_deadline_at", nullable = false)
    private Instant evidenceDeadlineAt;

    @Column(name = "buyer_statement", length = 2000)
    private String buyerStatement;

    @Column(name = "seller_statement", length = 2000)
    private String sellerStatement;

    /** 买方确认已读卖方陈述的时刻（null = 未确认）。 */
    @Column(name = "buyer_read_at")
    private Instant buyerReadAt;

    /** 卖方确认已读买方陈述的时刻（null = 未确认）。 */
    @Column(name = "seller_read_at")
    private Instant sellerReadAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** JPA 要求的无参构造。业务代码请用下面的公开构造器。 */
    protected DisputeSession() {
    }

    /**
     * 新建一份争议会话。
     *
     * @param orderId            订单号（= 主键）
     * @param openedAt           争议发起时刻
     * @param evidenceDeadlineAt 证据提交截止（发起时刻 + 窗口）
     */
    public DisputeSession(long orderId, Instant openedAt, Instant evidenceDeadlineAt) {
        if (openedAt == null || evidenceDeadlineAt == null) {
            throw new EscrowException("争议会话：时刻不可为空");
        }
        this.orderId = orderId;
        this.openedAt = openedAt;
        this.evidenceDeadlineAt = evidenceDeadlineAt;
        this.updatedAt = openedAt;
    }

    /**
     * 从持久化字段重建陈述状态机（服务推进前调用）。
     *
     * <p>回放顺序即当初的提交顺序：先两侧陈述、再两侧已读——{@link DisputeStatementFlow#markRead}
     * 要求"对方已陈述"，故已读的回放必须晚于陈述。
     */
    DisputeStatementFlow toFlow(long buyerId, long sellerId) {
        DisputeStatementFlow flow = new DisputeStatementFlow(buyerId, sellerId);
        if (buyerStatement != null) {
            flow.submit(buyerId, buyerStatement);
        }
        if (sellerStatement != null) {
            flow.submit(sellerId, sellerStatement);
        }
        if (buyerReadAt != null && sellerStatement != null) {
            flow.markRead(buyerId, sellerId);
        }
        if (sellerReadAt != null && buyerStatement != null) {
            flow.markRead(sellerId, buyerId);
        }
        return flow;
    }

    /** 用推进后的状态机回写持久化字段（已读时刻一经落定不再改写）。 */
    void applyFlow(DisputeStatementFlow flow, long buyerId, long sellerId, Instant now) {
        this.buyerStatement = flow.statementOf(buyerId).orElse(null);
        this.sellerStatement = flow.statementOf(sellerId).orElse(null);
        this.buyerReadAt = flow.hasRead(buyerId)
                ? (buyerReadAt != null ? buyerReadAt : now) : null;
        this.sellerReadAt = flow.hasRead(sellerId)
                ? (sellerReadAt != null ? sellerReadAt : now) : null;
        this.updatedAt = now;
    }

    public Long getOrderId() {
        return orderId;
    }

    public Instant getOpenedAt() {
        return openedAt;
    }

    public Instant getEvidenceDeadlineAt() {
        return evidenceDeadlineAt;
    }

    public String getBuyerStatement() {
        return buyerStatement;
    }

    public String getSellerStatement() {
        return sellerStatement;
    }

    public Instant getBuyerReadAt() {
        return buyerReadAt;
    }

    public Instant getSellerReadAt() {
        return sellerReadAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
