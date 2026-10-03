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
import java.util.Arrays;

/**
 * 担保交易订单账本（表 {@code escrow_orders}）。
 *
 * <p>表结构由 Flyway 管理（{@code V1__init_escrow.sql}），JPA 侧 {@code ddl-auto: validate}——
 * 实体不许自行建表或改表，结构漂移在启动期就失败。
 *
 * <h2>状态机</h2>
 * <pre>
 * OPEN ──markConfirmed──▶ CONFIRMED ──┐
 *  (已创建)                (卖方确认)   │
 *   │                                  │
 *   └──────── markLocked ──────────────┴──▶ LOCKED ──┬─ markReleased ─▶ RELEASED（终态）
 *                        (已锁仓)                    ├─ markRefunded ─▶ REFUNDED（终态）
 *                                                    ├─ markDelivered ─▶ DELIVERED ──┐
 *                                                    └─ markDisputed ─▶ DISPUTED ◀───┘
 *                                                                          │
 *                                            DISPUTED ─┬─ markReleased ─▶ RELEASED
 *                                                      └─ markRefunded ─▶ REFUNDED
 * </pre>
 *
 * <h2>金额为什么是 BigDecimal</h2>
 * <p>{@code DECIMAL(24,8)} 是精确十进制。用 {@code double} 表示金额会在比对与求差时
 * 引入不可解释的尾差——在担保交易里这是<b>资金问题</b>，不是显示问题。
 *
 * <h2>fail-closed 的两处落点</h2>
 * <ol>
 *   <li><b>非法迁移一律抛异常</b>：静默接受越级迁移（如 OPEN 直接 RELEASED）等于凭空放款；</li>
 *   <li><b>未知状态值亦抛异常</b>：从库中读到不在枚举内的状态（数据被篡改 / 版本漂移）时
 *       拒绝继续推进，而不是让 {@link IllegalArgumentException} 逃逸、或按最宽松状态放行。</li>
 * </ol>
 *
 * <p>各 {@code markX} 只做「守卫 + 迁移」，不含任何编排；编排与业务前置校验属服务层，
 * 且守卫必须<b>先于</b>任何不可回滚的外部动作。
 */
@Entity
@Table(name = "escrow_orders")
public class EscrowOrder {

    /** 担保订单状态。取值即流程节点，与 {@code escrow_orders.state} 列的字符串一一对应。 */
    public enum State {
        /** 已创建（待卖方确认）。 */
        OPEN,
        /** 卖方已确认（待买方托管资金）。 */
        CONFIRMED,
        /** 已锁仓（资金托管中）。 */
        LOCKED,
        /** 卖方已交付（待买方验收）。 */
        DELIVERED,
        /** 争议中（暂停自动结算，待裁决）。 */
        DISPUTED,
        /** 已放款给卖家（终态）。 */
        RELEASED,
        /** 已退款给买家（终态）。 */
        REFUNDED,
        /**
         * 已取消（协商取消，或未托管前超时关闭）。
         *
         * <p><b>只允许在资金未托管时进入</b>（{@code OPEN} / {@code CONFIRMED}）。
         * 托管后若要退出，必须走退款路径——否则"就地取消"会绕过资金流程，
         * 让一笔已锁定的钱凭空消失。
         */
        CANCELLED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    /**
     * 乐观锁版本号（JPA {@code @Version}）。
     *
     * <p>订单是「读 → 判状态 → 改状态 → 写回」的形态，并发下两个操作者可能读到同一快照；
     * 没有版本号时后写者会<b>静默覆盖</b>先写者（例如买方 cancel 覆盖卖方的 markDelivered，
     * 留下状态自相矛盾的订单）。加上它之后，提交期版本不一致即失败——宁可让一方重查。
     */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "buyer_user_id", nullable = false)
    private long buyerUserId;

    @Column(name = "seller_user_id", nullable = false)
    private long sellerUserId;

    @Column(name = "amount", nullable = false, precision = 24, scale = 8)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 16)
    private String currency;

    @Column(name = "state", nullable = false, length = 16)
    private String state;

    @Column(name = "reason", length = 512)
    private String reason;

    /**
     * 链上托管合约地址（S5 部署，V10 迁移）；{@code null} = 尚未部署。
     *
     * <p>由部署编排在<b>发送部署消息之前</b>写入（先落库、后发送）——一旦链上出现合约，
     * 链下必须有记录；发送失败时地址仍在库中，可原样重试（同参数推导出同地址）。
     */
    @Column(name = "chain_contract_address", length = 80)
    private String chainContractAddress;

    /** 链上托管金额（nanoton 十进制串，V11；部署时绑定，防改绑）。 */
    @Column(name = "chain_amount_nano", length = 32)
    private String chainAmountNano;

    /** 买方 TON 钱包地址（V11；TON Connect 连接后绑定，部署前可更新）。 */
    @Column(name = "buyer_ton_address", length = 80)
    private String buyerTonAddress;

    /** 卖方 TON 钱包地址（V11；同上）。 */
    @Column(name = "seller_ton_address", length = 80)
    private String sellerTonAddress;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** JPA 要求的无参构造。业务代码请用下面的公开构造器。 */
    protected EscrowOrder() {
    }

    /** 新建一笔待锁仓的担保订单：状态恒从 {@link State#OPEN} 起步。 */
    public EscrowOrder(long buyerUserId, long sellerUserId, BigDecimal amount,
                       String currency, Instant createdAt) {
        this.buyerUserId = buyerUserId;
        this.sellerUserId = sellerUserId;
        this.amount = amount;
        this.currency = currency;
        this.state = State.OPEN.name();
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    /** 卖方确认接单：{@code OPEN} → {@code CONFIRMED}。 */
    public void markConfirmed(Instant now) {
        requireState(State.OPEN);
        this.state = State.CONFIRMED.name();
        touch(now);
    }

    /**
     * 锁仓成功：{@code OPEN} 或 {@code CONFIRMED} → {@code LOCKED}。
     *
     * <p>允许从 {@code OPEN} 直接锁仓：既有骨架流程没有独立的"卖方确认"节点，
     * {@code CONFIRMED} 是为三步创建流程（买方创建 → 卖方确认 → 资金锁定）预留的中间态。
     */
    public void markLocked(Instant now) {
        requireState(State.OPEN, State.CONFIRMED);
        this.state = State.LOCKED.name();
        touch(now);
    }

    /** 卖方交付：{@code LOCKED} → {@code DELIVERED}（待买方验收）。 */
    public void markDelivered(Instant now) {
        requireState(State.LOCKED);
        this.state = State.DELIVERED.name();
        touch(now);
    }

    /** 发起争议：{@code LOCKED} 或 {@code DELIVERED} → {@code DISPUTED}（交付后也能争议）。 */
    public void markDisputed(String reason, Instant now) {
        requireState(State.LOCKED, State.DELIVERED);
        this.reason = reason;
        this.state = State.DISPUTED.name();
        touch(now);
    }

    /** 放款给卖家：{@code LOCKED} / {@code DELIVERED} / {@code DISPUTED} → {@code RELEASED}。 */
    public void markReleased(Instant now) {
        requireState(State.LOCKED, State.DELIVERED, State.DISPUTED);
        this.state = State.RELEASED.name();
        touch(now);
    }

    /** 退款给买家：{@code LOCKED} / {@code DELIVERED} / {@code DISPUTED} → {@code REFUNDED}。 */
    public void markRefunded(String reason, Instant now) {
        requireState(State.LOCKED, State.DELIVERED, State.DISPUTED);
        this.reason = reason;
        this.state = State.REFUNDED.name();
        touch(now);
    }

    /**
     * 取消订单：{@code OPEN} 或 {@code CONFIRMED} → {@code CANCELLED}。
     *
     * <p><b>刻意不允许从 {@code LOCKED} / {@code DELIVERED} 取消</b>：那时资金已托管，
     * 退出必须走 {@link #markRefunded}。这条守卫保证「资金已动」的订单
     * 永远无法绕过资金流程被就地取消。
     */
    public void markCancelled(String reason, Instant now) {
        requireState(State.OPEN, State.CONFIRMED);
        this.reason = reason;
        this.state = State.CANCELLED.name();
        touch(now);
    }

    /**
     * 绑定链上托管合约地址（S5 部署编排专用；先落库、后发送）。
     *
     * <p><b>一次性且防改绑</b>：已有值且与新值不同即拒——静默改绑会让链下记账指向
     * 另一个合约（那里的资金与订单无关），是不可回滚的错位。同值重复调用是幂等的
     * （部署失败重试的路径）。
     */
    public void attachChainContractAddress(String address, Instant now) {
        if (address == null || address.isBlank()) {
            throw new EscrowException("订单：链上合约地址不可为空");
        }
        if (this.chainContractAddress != null
                && !this.chainContractAddress.equals(address)) {
            throw new EscrowException("订单已有链上合约地址（" + this.chainContractAddress
                    + "），拒绝改绑到 " + address + idSuffix());
        }
        this.chainContractAddress = address;
        touch(now);
    }

    /** 链上托管合约地址（{@code null} = 尚未部署）。 */
    public String getChainContractAddress() {
        return chainContractAddress;
    }

    /**
     * 绑定链上托管金额（nanoton 十进制字符串，V11）。<b>一次性且防改绑</b>——金额是部署参数，
     * 落库后即与链上合约绑定；静默改绑会让用户钱包按错误金额付款。同值幂等（部署重试路径）。
     */
    public void attachChainAmount(String amountNano, Instant now) {
        if (amountNano == null || amountNano.isBlank()) {
            throw new EscrowException("订单：链上金额不可为空");
        }
        if (this.chainAmountNano != null && !this.chainAmountNano.equals(amountNano)) {
            throw new EscrowException("订单已有链上金额（" + this.chainAmountNano
                    + "），拒绝改绑到 " + amountNano + idSuffix());
        }
        this.chainAmountNano = amountNano;
        touch(now);
    }

    /**
     * 绑定买方 TON 钱包地址（V11）。<b>部署前可更新</b>（用户可能重连换钱包，以最近一次连接为准）；
     * <b>部署后不可改绑</b>——地址已写进链上 storage（Fund 守卫 {@code sender==storage.buyer}），
     * 改库只会让链下记账与链上权威不一致。同值幂等。
     */
    public void attachBuyerTonAddress(String address, Instant now) {
        this.buyerTonAddress = attachUserTonAddress(this.buyerTonAddress, address, "买方", now);
    }

    /** 绑定卖方 TON 钱包地址（V11；语义同 {@link #attachBuyerTonAddress}）。 */
    public void attachSellerTonAddress(String address, Instant now) {
        this.sellerTonAddress = attachUserTonAddress(this.sellerTonAddress, address, "卖方", now);
    }

    private String attachUserTonAddress(String current, String address, String role, Instant now) {
        if (address == null || address.isBlank()) {
            throw new EscrowException("订单：" + role + " TON 地址不可为空");
        }
        if (this.chainContractAddress != null && current != null && !current.equals(address)) {
            throw new EscrowException("订单已部署链上合约——" + role
                    + " TON 地址不可改绑（链上 storage 已定）" + idSuffix());
        }
        touch(now);
        return address;
    }

    /** 链上托管金额（nanoton 十进制串；{@code null} = 未绑定）。 */
    public String getChainAmountNano() {
        return chainAmountNano;
    }

    /** 买方 TON 钱包地址（V11；{@code null} = 未绑定）。 */
    public String getBuyerTonAddress() {
        return buyerTonAddress;
    }

    /** 卖方 TON 钱包地址（V11；{@code null} = 未绑定）。 */
    public String getSellerTonAddress() {
        return sellerTonAddress;
    }

    /** 当前状态（业务侧读取入口）。未知值 fail-closed。 */
    public State currentState() {
        if (this.state == null) {
            throw new EscrowException("担保订单状态缺失（fail-closed）" + idSuffix());
        }
        try {
            return State.valueOf(this.state);
        } catch (IllegalArgumentException ex) {
            // 不让 JDK 异常逃逸：上层需要能把它识别为"数据异常、需人工介入"
            throw new EscrowException("担保订单状态非法（fail-closed）：" + this.state + idSuffix(), ex);
        }
    }

    private void requireState(State... allowed) {
        State current = currentState();
        for (State candidate : allowed) {
            if (current == candidate) {
                return;
            }
        }
        throw new EscrowException("担保订单状态迁移非法：当前 " + current + "，本操作只允许 "
                + Arrays.toString(allowed) + idSuffix());
    }

    private void touch(Instant now) {
        this.updatedAt = now;
    }

    private String idSuffix() {
        return id == null ? "" : "（订单 " + id + "）";
    }

    public Long getId() {
        return id;
    }

    /**
     * 回填主键——供<b>非 JPA</b> 的存储实现（内存 / 测试替身）使用。
     *
     * <p>JPA 实现由容器在 {@code save} 后自动生成 id，无需调用本方法；
     * 内存实现没有容器代劳，必须自行回填，否则上层读到的是 {@code null}。
     */
    public void assignId(long id) {
        this.id = id;
    }

    public long getBuyerUserId() {
        return buyerUserId;
    }

    public long getSellerUserId() {
        return sellerUserId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    /** 持久化用的字符串状态。业务判断请用 {@link #currentState()}。 */
    public String getState() {
        return state;
    }

    public String getReason() {
        return reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
