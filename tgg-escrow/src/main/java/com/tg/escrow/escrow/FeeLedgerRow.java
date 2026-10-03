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

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 放款费用台账行（V12）——{@link FeeLedgerPort.Entry} 的持久化形态。
 *
 * <p>{@code order_id} 唯一：每单至多一条（重放/重试幂等）。
 * {@code recorded_at} 由数据库生成（DEFAULT CURRENT_TIMESTAMP），实体只读。
 */
@Entity
@Table(name = "fee_ledger")
public class FeeLedgerRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false, unique = true)
    private long orderId;

    @Column(name = "currency", nullable = false, length = 16)
    private String currency;

    @Column(name = "gross_amount", nullable = false, precision = 24, scale = 8)
    private BigDecimal grossAmount;

    @Column(name = "platform_fee", nullable = false, precision = 24, scale = 8)
    private BigDecimal platformFee;

    @Column(name = "seller_net", nullable = false, precision = 24, scale = 8)
    private BigDecimal sellerNet;

    @Column(name = "first_order_waived", nullable = false)
    private boolean firstOrderWaived;

    /** 由数据库默认值生成（insertable=false），映射为只读。 */
    @Column(name = "recorded_at", nullable = false, insertable = false, updatable = false)
    private Instant recordedAt;

    protected FeeLedgerRow() {
        // JPA
    }

    public FeeLedgerRow(long orderId, String currency, BigDecimal grossAmount,
                        BigDecimal platformFee, BigDecimal sellerNet, boolean firstOrderWaived) {
        this.orderId = orderId;
        this.currency = currency;
        this.grossAmount = grossAmount;
        this.platformFee = platformFee;
        this.sellerNet = sellerNet;
        this.firstOrderWaived = firstOrderWaived;
    }

    public Long getId() {
        return id;
    }

    public long getOrderId() {
        return orderId;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getGrossAmount() {
        return grossAmount;
    }

    public BigDecimal getPlatformFee() {
        return platformFee;
    }

    public BigDecimal getSellerNet() {
        return sellerNet;
    }

    public boolean isFirstOrderWaived() {
        return firstOrderWaived;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }
}
