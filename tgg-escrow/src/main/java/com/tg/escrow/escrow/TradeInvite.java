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
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 待接受邀请（表 {@code trade_invites}）——「先建邀请，后绑卖方」的载体。
 *
 * <h2>为什么是独立聚合，而不是给订单加个「待接受」态</h2>
 * <p>担保订单 {@link EscrowOrder} 的不变量是「<b>买卖双方恒在</b>」——取消权限、争议参与、
 * 交付取证都建立在此之上（{@code EscrowTradeService.cancel}、{@code DisputeFlow}、
 * {@code TradeReview}）。若把卖方改成可空，这些资金路径会引入拆箱 NPE 风险，且新增的非终态
 * 会被准入门禁的「进行中」集合自动计入（{@code JpaTradeHistoryPort} 由枚举派生）。
 * 把「待接受」这一有独立生命周期（令牌 / 有效期 / 一次性）的阶段放进本聚合，
 * 订单账本与其状态机得以<b>零改动</b>；接单成功那一刻才物化出一笔双方齐全的订单。
 *
 * <h2>一次性</h2>
 * <p>一条邀请只能被接受一次：接受即写入 {@code acceptedOrderId}。并发抢先由 {@link Version}
 * 乐观锁兜底——两个接受者都读到未消费的快照时，后提交者版本冲突而失败（见
 * {@link JpaTradeInviteStore}）。
 *
 * <h2>fail-closed</h2>
 * <p>构造期拒绝：令牌空白、金额非正、币种不在 {@link TradeCurrency} 白名单、有效期不晚于创建时刻。
 * {@link #requireAcceptable} 拒绝：已消费、已过期、自邀自接——每种都带机器可判的
 * {@link TradeInviteException.Reason}。
 */
@Entity
@Table(name = "trade_invites")
public class TradeInvite {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /** 一次性邀请令牌（随机、不可枚举）；作为深链 {@code startapp} 参数下发。 */
    @Column(name = "token", nullable = false, length = 64)
    private String token;

    @Column(name = "buyer_user_id", nullable = false)
    private long buyerUserId;

    @Column(name = "amount", nullable = false, precision = 24, scale = 8)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 16)
    private String currency;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** 被接受后物化出的订单号；{@code null} 表示尚未被接受。 */
    @Column(name = "accepted_order_id")
    private Long acceptedOrderId;

    /** JPA 要求的无参构造。业务代码请用公开构造器。 */
    protected TradeInvite() {
    }

    /**
     * 新建一条待接受邀请。
     *
     * @param buyerUserId 发起人（买方）ID
     * @param amount      金额（必须为正）
     * @param currency    币种（须在 TON / USDT(TON 链) 白名单内；大小写/空白归一）
     * @param token       一次性令牌（不得空白）
     * @param createdAt   创建时刻
     * @param expiresAt   失效时刻（必须晚于创建时刻）
     */
    public TradeInvite(long buyerUserId, BigDecimal amount, String currency,
                       String token, Instant createdAt, Instant expiresAt) {
        if (token == null || token.isBlank()) {
            throw new EscrowException("邀请创建：令牌未提供");
        }
        if (buyerUserId <= 0) {
            throw new EscrowException("邀请创建：发起人 ID 非法（" + buyerUserId + "）");
        }
        if (amount == null) {
            throw new EscrowException("邀请创建：金额未提供");
        }
        if (amount.signum() <= 0) {
            throw new EscrowException("邀请创建：金额必须为正数，实为 " + amount);
        }
        if (createdAt == null) {
            throw new EscrowException("邀请创建：创建时刻未提供");
        }
        if (expiresAt == null) {
            throw new EscrowException("邀请创建：有效期未提供");
        }
        if (!expiresAt.isAfter(createdAt)) {
            throw new EscrowException("邀请创建：有效期必须晚于创建时刻（否则一出生即失效）");
        }
        this.buyerUserId = buyerUserId;
        this.amount = amount;
        this.currency = TradeCurrency.requireSupported(currency).name();
        this.token = token;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    /** 是否已被接受（消费即终端）。 */
    public boolean isConsumed() {
        return acceptedOrderId != null;
    }

    /**
     * 接单前置守卫——不满足即抛 {@link TradeInviteException}，绝不静默放行。
     *
     * <p>优先级（多条同时成立时只报首个）：<b>已消费 &gt; 已过期 &gt; 自邀自接</b>。
     * 已被接受是终局事实，最先告知；过期是时间条件；自邀自接是调用方错误。
     *
     * @param acceptorId 接受者（卖方）ID
     * @param now        判定时刻（边界闭区间：恰好到期仍可接受）
     */
    public void requireAcceptable(long acceptorId, Instant now) {
        if (isConsumed()) {
            throw new TradeInviteException(TradeInviteException.Reason.ALREADY_ACCEPTED,
                    "该邀请已被他人接受");
        }
        if (now == null) {
            throw new EscrowException("邀请接受：判定时刻不可为空");
        }
        if (now.isAfter(expiresAt)) {
            throw new TradeInviteException(TradeInviteException.Reason.EXPIRED,
                    "该邀请已过期（有效期至 " + expiresAt + "）");
        }
        if (acceptorId == buyerUserId) {
            throw new TradeInviteException(TradeInviteException.Reason.SELF_ACCEPT,
                    "不能接受自己发起的邀请——自交易会让担保失去意义");
        }
    }

    /**
     * 标记已被接受，并记录物化出的订单号。
     *
     * @param orderId 接单生成的订单号
     * @throws EscrowException 该邀请已被接受（不可重复消费）
     */
    public void markAccepted(long orderId) {
        if (isConsumed()) {
            throw new EscrowException("邀请已被接受，不可重复消费（订单 " + acceptedOrderId + "）");
        }
        this.acceptedOrderId = orderId;
    }

    /**
     * 回填主键——供<b>非 JPA</b> 的存储实现（内存 / 测试替身）使用。
     * JPA 实现由容器在 {@code save} 后自动生成 id。
     */
    public void assignId(long id) {
        this.id = id;
    }

    public Long getId() {
        return id;
    }

    public String getToken() {
        return token;
    }

    public long getBuyerUserId() {
        return buyerUserId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Long getAcceptedOrderId() {
        return acceptedOrderId;
    }
}
