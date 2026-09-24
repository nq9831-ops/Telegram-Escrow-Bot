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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 交易生命周期服务的行为固定测试（Wave 1）——把订单从「停在 CONFIRMED」推向可走完全程。
 *
 * <h2>它守的是什么</h2>
 * <p>状态机的迁移方法（{@code markLocked/markDelivered/markReleased/markRefunded/markDisputed}）
 * 早已就位，却<b>没有任何生产调用方</b>——用户把订单建出来之后就再也推不动它。本类把
 * 「谁能在什么状态下推到哪一步」逐条钉住，并断言<b>被拒时状态不变</b>
 * （只断言文案会漏掉"文案对了、状态却偷偷变了"这类缺陷）。
 *
 * <p>纯本地：真实服务 + 真实门禁 + 内存存储，不连库、不启 Spring。
 */
class EscrowLifecycleTest {

    private static final Instant NOW = Instant.parse("2026-09-24T10:00:00Z");
    private static final Clock FIXED = Clock.fixed(NOW, ZoneOffset.UTC);

    private static final long BUYER = 1001L;
    private static final long SELLER = 2002L;
    private static final long STRANGER = 9999L;
    private static final BigDecimal AMOUNT = new BigDecimal("100.00");

    /** 记录保存次数与最近一次保存的订单；可注入并发冲突。 */
    static final class RecordingStore implements EscrowOrderStore {
        final AtomicInteger saves = new AtomicInteger();
        RuntimeException failOnSave;
        long nextId = 1;

        @Override
        public EscrowOrder save(EscrowOrder order) {
            if (failOnSave != null) {
                throw failOnSave;
            }
            saves.incrementAndGet();
            order.assignId(nextId++);
            return order;
        }
    }

    private static EscrowTradeService service(RecordingStore store) {
        TradeAdmissionGate gate = new TradeAdmissionGate(new TradeAdmissionPolicy(5, Duration.ZERO));
        TradeHistoryPort history = id -> new TradeAdmissionContext(id, 0, null, false);
        return new EscrowTradeService(gate, history, store, FIXED);
    }

    private static EscrowTradeService service() {
        return service(new RecordingStore());
    }

    /** 新建一笔处于 OPEN 的订单（走真实构造器）。 */
    private static EscrowOrder openOrder() {
        return new EscrowOrder(BUYER, SELLER, AMOUNT, "USDT", NOW);
    }

    /** 推进到 LOCKED（买方托管登记）。 */
    private static EscrowOrder lockedOrder() {
        EscrowOrder order = openOrder();
        order.markLocked(NOW);
        return order;
    }

    /** 推进到 DELIVERED（卖方已交付）。 */
    private static EscrowOrder deliveredOrder() {
        EscrowOrder order = lockedOrder();
        order.markDelivered(NOW);
        return order;
    }

    @Nested
    @DisplayName("lock：买方托管登记")
    class Lock {

        @Test
        @DisplayName("买方 lock → OPEN 迁移到 LOCKED 并落库")
        void buyerLocks() {
            RecordingStore store = new RecordingStore();
            EscrowOrder order = openOrder();

            EscrowOrder saved = service(store).lock(order, BUYER);

            assertThat(saved.currentState()).isEqualTo(EscrowOrder.State.LOCKED);
            assertThat(store.saves.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("CONFIRMED 也能 lock（邀请接单后的订单正处此态）")
        void confirmedCanLock() {
            EscrowOrder order = openOrder();
            order.markConfirmed(NOW);

            assertThat(service().lock(order, BUYER).currentState())
                    .isEqualTo(EscrowOrder.State.LOCKED);
        }

        @Test
        @DisplayName("卖方发 lock → 拒，且状态不变（非当事人不得动钱）")
        void sellerCannotLock() {
            EscrowOrder order = openOrder();

            assertThatThrownBy(() -> service().lock(order, SELLER))
                    .isInstanceOf(EscrowException.class)
                    .hasMessageContaining("无权");
            assertThat(order.currentState()).isEqualTo(EscrowOrder.State.OPEN);
        }

        @Test
        @DisplayName("已交付的订单再 lock → 状态守卫拒（不能回退）")
        void deliveredCannotLock() {
            EscrowOrder order = deliveredOrder();

            assertThatThrownBy(() -> service().lock(order, BUYER))
                    .isInstanceOf(EscrowException.class);
            assertThat(order.currentState()).isEqualTo(EscrowOrder.State.DELIVERED);
        }
    }

    @Nested
    @DisplayName("deliver：卖方交付")
    class Deliver {

        @Test
        @DisplayName("卖方 deliver → LOCKED 迁移到 DELIVERED")
        void sellerDelivers() {
            EscrowOrder order = lockedOrder();

            assertThat(service().deliver(order, SELLER).currentState())
                    .isEqualTo(EscrowOrder.State.DELIVERED);
        }

        @Test
        @DisplayName("买方发 deliver → 拒，且状态不变")
        void buyerCannotDeliver() {
            EscrowOrder order = lockedOrder();

            assertThatThrownBy(() -> service().deliver(order, BUYER))
                    .isInstanceOf(EscrowException.class)
                    .hasMessageContaining("无权");
            assertThat(order.currentState()).isEqualTo(EscrowOrder.State.LOCKED);
        }

        @Test
        @DisplayName("未锁仓即交付 → 拒（资金未托管，交付无对象）")
        void cannotDeliverBeforeLock() {
            EscrowOrder order = openOrder();

            assertThatThrownBy(() -> service().deliver(order, SELLER))
                    .isInstanceOf(EscrowException.class);
            assertThat(order.currentState()).isEqualTo(EscrowOrder.State.OPEN);
        }
    }

    @Nested
    @DisplayName("release / refund：结算")
    class Settlement {

        @Test
        @DisplayName("买方 release → DELIVERED 迁移到 RELEASED")
        void buyerReleases() {
            EscrowOrder order = deliveredOrder();

            assertThat(service().release(order, BUYER).currentState())
                    .isEqualTo(EscrowOrder.State.RELEASED);
        }

        @Test
        @DisplayName("卖方发 release → 拒，且状态不变（不能自己给自己放款）")
        void sellerCannotRelease() {
            EscrowOrder order = deliveredOrder();

            assertThatThrownBy(() -> service().release(order, SELLER))
                    .isInstanceOf(EscrowException.class)
                    .hasMessageContaining("无权");
            assertThat(order.currentState()).isEqualTo(EscrowOrder.State.DELIVERED);
        }

        @Test
        @DisplayName("争议中仍可放款（买方与卖方和解）")
        void releaseFromDisputed() {
            EscrowOrder order = deliveredOrder();
            order.markDisputed("争议", NOW);

            assertThat(service().release(order, BUYER).currentState())
                    .isEqualTo(EscrowOrder.State.RELEASED);
        }

        @Test
        @DisplayName("refund：买卖任一方可发起协商退款")
        void eitherPartyCanRefund() {
            EscrowOrder byBuyer = lockedOrder();
            assertThat(service().refund(byBuyer, BUYER, "协商退款").currentState())
                    .isEqualTo(EscrowOrder.State.REFUNDED);

            EscrowOrder bySeller = lockedOrder();
            assertThat(service().refund(bySeller, SELLER, "无法履约").currentState())
                    .isEqualTo(EscrowOrder.State.REFUNDED);
        }

        @Test
        @DisplayName("第三方 refund → 拒，且状态不变")
        void strangerCannotRefund() {
            EscrowOrder order = lockedOrder();

            assertThatThrownBy(() -> service().refund(order, STRANGER, "路人退款"))
                    .isInstanceOf(EscrowException.class)
                    .hasMessageContaining("无权");
            assertThat(order.currentState()).isEqualTo(EscrowOrder.State.LOCKED);
        }

        @Test
        @DisplayName("未锁仓即放款 → 拒（守卫在状态机，服务层不重复实现第二套）")
        void cannotReleaseBeforeLock() {
            EscrowOrder order = openOrder();

            assertThatThrownBy(() -> service().release(order, BUYER))
                    .isInstanceOf(EscrowException.class);
            assertThat(order.currentState()).isEqualTo(EscrowOrder.State.OPEN);
        }
    }

    @Nested
    @DisplayName("dispute：争议发起（接线 DisputeFlow）")
    class Dispute {

        @Test
        @DisplayName("锁仓后买卖任一方可发起争议，理由被记录")
        void eitherPartyCanDispute() {
            EscrowOrder order = lockedOrder();

            EscrowOrder saved = service().dispute(order, SELLER, "未收到货");

            assertThat(saved.currentState()).isEqualTo(EscrowOrder.State.DISPUTED);
            assertThat(saved.getReason()).isEqualTo("未收到货");
        }

        @Test
        @DisplayName("未锁仓（OPEN）发起争议 → 拒，且状态不变（无争议对象）")
        void cannotDisputeBeforeLock() {
            EscrowOrder order = openOrder();

            assertThatThrownBy(() -> service().dispute(order, BUYER, "想冻结"))
                    .isInstanceOf(EscrowException.class);
            assertThat(order.currentState()).isEqualTo(EscrowOrder.State.OPEN);
        }

        @Test
        @DisplayName("第三方发起争议 → 拒（路人不得冻结他人资金）")
        void strangerCannotDispute() {
            EscrowOrder order = lockedOrder();

            assertThatThrownBy(() -> service().dispute(order, STRANGER, "围观"))
                    .isInstanceOf(EscrowException.class);
            assertThat(order.currentState()).isEqualTo(EscrowOrder.State.LOCKED);
        }

        @Test
        @DisplayName("争议理由空白 → 拒（空理由等于没记原因）")
        void blankReasonRejected() {
            EscrowOrder order = lockedOrder();

            assertThatThrownBy(() -> service().dispute(order, BUYER, "   "))
                    .isInstanceOf(EscrowException.class);
            assertThat(order.currentState()).isEqualTo(EscrowOrder.State.LOCKED);
        }
    }

    @Nested
    @DisplayName("并发与端口契约")
    class Concurrency {

        @Test
        @DisplayName("保存触发乐观锁冲突 → 原样上抛，绝不谎报成功")
        void concurrentConflictPropagates() {
            RecordingStore store = new RecordingStore();
            store.failOnSave = new ConcurrentOrderUpdateException("订单已被他人变更");

            assertThatThrownBy(() -> service(store).lock(openOrder(), BUYER))
                    .isInstanceOf(ConcurrentOrderUpdateException.class);
        }

        @Test
        @DisplayName("订单为 null → 抛（不 NPE）")
        void nullOrderFailsClosed() {
            assertThatThrownBy(() -> service().lock(null, BUYER))
                    .isInstanceOf(EscrowException.class);
        }
    }
}
