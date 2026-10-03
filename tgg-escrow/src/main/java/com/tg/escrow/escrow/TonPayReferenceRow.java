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

/** TON Pay 支付引用行（V13）——登记与结算痕迹。 */
@Entity
@Table(name = "tonpay_references")
public class TonPayReferenceRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private long orderId;

    @Column(name = "reference", nullable = false, unique = true, length = 128)
    private String reference;

    @Column(name = "body_base64_hash", length = 128)
    private String bodyBase64Hash;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "tx_hash", length = 128)
    private String txHash;

    @Column(name = "settled_amount", precision = 24, scale = 8)
    private BigDecimal settledAmount;

    @Column(name = "settled_currency", length = 16)
    private String settledCurrency;

    @Column(name = "settled_at")
    private Instant settledAt;

    protected TonPayReferenceRow() {
        // JPA
    }

    public TonPayReferenceRow(long orderId, String reference, String bodyBase64Hash) {
        this.orderId = orderId;
        this.reference = reference;
        this.bodyBase64Hash = bodyBase64Hash;
    }

    public Long getId() {
        return id;
    }

    public long getOrderId() {
        return orderId;
    }

    public String getReference() {
        return reference;
    }

    public String getBodyBase64Hash() {
        return bodyBase64Hash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getTxHash() {
        return txHash;
    }

    public BigDecimal getSettledAmount() {
        return settledAmount;
    }

    public String getSettledCurrency() {
        return settledCurrency;
    }

    public Instant getSettledAt() {
        return settledAt;
    }

    void markSettled(String txHash, BigDecimal amount, String currency, Instant at) {
        this.txHash = txHash;
        this.settledAmount = amount;
        this.settledCurrency = currency;
        this.settledAt = at;
    }
}
