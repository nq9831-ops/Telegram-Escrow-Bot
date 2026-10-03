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
package com.tg.escrow;

import com.tg.escrow.chain.ChainGateway;
import com.tg.escrow.chain.ChainUnavailableException;
import com.tg.escrow.escrow.EscrowOrder;
import com.tg.escrow.escrow.EscrowOrderLookupPort;
import com.tg.escrow.escrow.EscrowOrderStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 链上对账（ET-19 步 2）的判定矩阵——每个方向都钉住「判定 + 动作 + 落库次数」。
 *
 * <p>核心口径：链上领先 → 按序回填（合法转移序列）；链下领先 → 不动作（流程登记常态）；
 * 危险不一致 → 只报告零动作；回填后重复对账 → 幂等一致。
 */
class EscrowChainReconcilerTest {

    private static final long BUYER = 1001L;
    private static final long SELLER = 2002L;
    private static final long ORDER = 77L;
    private static final Instant T0 = Instant.parse("2026-10-03T00:00:00Z");

    /** 内存订单查询 + 存储（save 回写——幂等用例仰赖它）。 */
    private static final class InMemory implements EscrowOrderLookupPort, EscrowOrderStore {
        final Map<Long, EscrowOrder> rows = new HashMap<>();
        int saves;

        @Override
        public Optional<EscrowOrder> byId(long orderId) {
            return Optional.ofNullable(rows.get(orderId));
        }

        @Override
        public java.util.List<EscrowOrder> recentFor(long userId, int limit) {
            return java.util.List.of();   // 对账不消费此方法
        }

        @Override
        public EscrowOrder save(EscrowOrder order) {
            saves++;
            rows.put(order.getId(), order);
            return order;
        }
    }

    private static EscrowOrder orderAt(long id, EscrowOrder.State target, boolean withChainAddress) {
        EscrowOrder order = new EscrowOrder(BUYER, SELLER, new BigDecimal("100"), "USDT", T0);
        order.assignId(id);
        if (withChainAddress) {
            order.attachChainContractAddress("EQit-" + id, T0);
        }
        switch (target) {
            case CONFIRMED -> order.markConfirmed(T0);
            case LOCKED -> order.markLocked(T0);
            case DELIVERED -> {
                order.markLocked(T0);
                order.markDelivered(T0);
            }
            case DISPUTED -> {
                order.markLocked(T0);
                order.markDisputed("争议", T0);
            }
            case RELEASED -> {
                order.markLocked(T0);
                order.markDelivered(T0);
                order.markReleased(T0);
            }
            case REFUNDED -> {
                order.markLocked(T0);
                order.markRefunded("退款", T0);
            }
            case CANCELLED -> order.markCancelled("取消", T0);
            default -> {
                // OPEN
            }
        }
        return order;
    }

    private InMemory store;
    private ChainGateway chain;

    private EscrowChainReconciler reconciler() {
        store = new InMemory();
        chain = mock(ChainGateway.class);
        return new EscrowChainReconciler(store, store, chain, Clock.fixed(T0, ZoneOffset.UTC));
    }

    private void givenOrderAt(EscrowOrder.State state) {
        store.rows.put(ORDER, orderAt(ORDER, state, true));
    }

    private void givenChainState(int state) throws Exception {
        when(chain.escrowStateOf("EQit-" + ORDER)).thenReturn(state);
    }

    @Test
    @DisplayName("订单不存在 → NOT_FOUND")
    void notFound() {
        EscrowChainReconciler reconciler = reconciler();

        EscrowChainReconciler.Result result = reconciler.reconcile(ORDER);

        assertThat(result.verdict()).isEqualTo(EscrowChainReconciler.Verdict.NOT_FOUND);
    }

    @Test
    @DisplayName("未部署合约（无链上地址）→ NOT_DEPLOYED、零动作")
    void notDeployed() {
        EscrowChainReconciler reconciler = reconciler();
        store.rows.put(ORDER, orderAt(ORDER, EscrowOrder.State.LOCKED, false));

        EscrowChainReconciler.Result result = reconciler.reconcile(ORDER);

        assertThat(result.verdict()).isEqualTo(EscrowChainReconciler.Verdict.NOT_DEPLOYED);
        assertThat(store.saves).isZero();
    }

    @Test
    @DisplayName("链上查询失败 → CHAIN_UNAVAILABLE 如实回报、零动作")
    void chainUnavailable() throws Exception {
        EscrowChainReconciler reconciler = reconciler();
        givenOrderAt(EscrowOrder.State.LOCKED);
        when(chain.escrowStateOf("EQit-" + ORDER))
                .thenThrow(new ChainUnavailableException("liteserver 全不可达"));

        EscrowChainReconciler.Result result = reconciler.reconcile(ORDER);

        assertThat(result.verdict()).isEqualTo(EscrowChainReconciler.Verdict.CHAIN_UNAVAILABLE);
        assertThat(result.note()).contains("不可达");
        assertThat(store.saves).isZero();
    }

    @Test
    @DisplayName("链上领先（OPEN + 链上 LOCKED）→ 回填 markLocked 并保存")
    void backfillsLock() throws Exception {
        EscrowChainReconciler reconciler = reconciler();
        givenOrderAt(EscrowOrder.State.OPEN);
        givenChainState(1);

        EscrowChainReconciler.Result result = reconciler.reconcile(ORDER);

        assertThat(result.verdict()).isEqualTo(EscrowChainReconciler.Verdict.BACKFILLED);
        assertThat(result.actions()).containsExactly("markLocked");
        assertThat(store.rows.get(ORDER).currentState()).isEqualTo(EscrowOrder.State.LOCKED);
        assertThat(store.saves).isEqualTo(1);
    }

    @Test
    @DisplayName("链上多跳（LOCKED + 链上 RELEASED）→ 按序 markDelivered,markReleased")
    void backfillsMultiHopToReleased() throws Exception {
        EscrowChainReconciler reconciler = reconciler();
        givenOrderAt(EscrowOrder.State.LOCKED);
        givenChainState(4);

        EscrowChainReconciler.Result result = reconciler.reconcile(ORDER);

        assertThat(result.actions()).containsExactly("markDelivered", "markReleased");
        assertThat(store.rows.get(ORDER).currentState()).isEqualTo(EscrowOrder.State.RELEASED);
    }

    @Test
    @DisplayName("链上退款（DISPUTED + 链上 REFUNDED）→ 回填 markRefunded")
    void backfillsRefund() throws Exception {
        EscrowChainReconciler reconciler = reconciler();
        givenOrderAt(EscrowOrder.State.DISPUTED);
        givenChainState(5);

        EscrowChainReconciler.Result result = reconciler.reconcile(ORDER);

        assertThat(result.actions()).containsExactly("markRefunded");
        assertThat(store.rows.get(ORDER).currentState()).isEqualTo(EscrowOrder.State.REFUNDED);
    }

    @Test
    @DisplayName("链上争议（DELIVERED + 链上 DISPUTED）→ 回填 markDisputed")
    void backfillsDispute() throws Exception {
        EscrowChainReconciler reconciler = reconciler();
        givenOrderAt(EscrowOrder.State.DELIVERED);
        givenChainState(3);

        EscrowChainReconciler.Result result = reconciler.reconcile(ORDER);

        assertThat(result.actions()).containsExactly("markDisputed");
        assertThat(store.rows.get(ORDER).currentState()).isEqualTo(EscrowOrder.State.DISPUTED);
    }

    @Test
    @DisplayName("一致（LOCKED + 链上 LOCKED；链下领先 DELIVERED + 链上 LOCKED）→ CONSISTENT 零动作")
    void consistentWhenAlignedOrLedgerAhead() throws Exception {
        EscrowChainReconciler reconciler = reconciler();
        givenOrderAt(EscrowOrder.State.LOCKED);
        givenChainState(1);

        assertThat(reconciler.reconcile(ORDER).verdict())
                .isEqualTo(EscrowChainReconciler.Verdict.CONSISTENT);

        // 链下领先（流程登记先行）——不动作
        store.rows.put(ORDER, orderAt(ORDER, EscrowOrder.State.DELIVERED, true));
        assertThat(reconciler.reconcile(ORDER).verdict())
                .isEqualTo(EscrowChainReconciler.Verdict.CONSISTENT);
        assertThat(store.saves).isZero();
    }

    @Test
    @DisplayName("危险：链下 RELEASED 但链上资金仍在合约（LOCKED）→ DANGER、零动作、状态不动")
    void dangerOnFinalOrderWithFundsHeld() throws Exception {
        EscrowChainReconciler reconciler = reconciler();
        givenOrderAt(EscrowOrder.State.RELEASED);
        givenChainState(1);

        EscrowChainReconciler.Result result = reconciler.reconcile(ORDER);

        assertThat(result.verdict()).isEqualTo(EscrowChainReconciler.Verdict.DANGER);
        assertThat(result.note()).contains("资金仍在合约");
        assertThat(store.rows.get(ORDER).currentState()).isEqualTo(EscrowOrder.State.RELEASED);
        assertThat(store.saves).isZero();
    }

    @Test
    @DisplayName("危险：资金方向相反（链下 REFUNDED vs 链上 RELEASED）→ DANGER、零动作")
    void dangerOnOppositeFinalDirections() throws Exception {
        EscrowChainReconciler reconciler = reconciler();
        givenOrderAt(EscrowOrder.State.REFUNDED);
        givenChainState(4);

        EscrowChainReconciler.Result result = reconciler.reconcile(ORDER);

        assertThat(result.verdict()).isEqualTo(EscrowChainReconciler.Verdict.DANGER);
        assertThat(result.note()).contains("方向相反");
        assertThat(store.saves).isZero();
    }

    @Test
    @DisplayName("链上路径未使用（链下 RELEASED + 链上 OPEN）→ CHAIN_UNUSED 信息级、零动作")
    void chainUnused() throws Exception {
        EscrowChainReconciler reconciler = reconciler();
        givenOrderAt(EscrowOrder.State.RELEASED);
        givenChainState(0);

        EscrowChainReconciler.Result result = reconciler.reconcile(ORDER);

        assertThat(result.verdict()).isEqualTo(EscrowChainReconciler.Verdict.CHAIN_UNUSED);
        assertThat(store.saves).isZero();
    }

    @Test
    @DisplayName("幂等：回填后重复对账 → CONSISTENT 且不再保存")
    void idempotentAfterBackfill() throws Exception {
        EscrowChainReconciler reconciler = reconciler();
        givenOrderAt(EscrowOrder.State.OPEN);
        givenChainState(1);

        assertThat(reconciler.reconcile(ORDER).verdict())
                .isEqualTo(EscrowChainReconciler.Verdict.BACKFILLED);
        assertThat(store.saves).isEqualTo(1);

        EscrowChainReconciler.Result second = reconciler.reconcile(ORDER);

        assertThat(second.verdict()).isEqualTo(EscrowChainReconciler.Verdict.CONSISTENT);
        assertThat(store.saves).as("第二次零动作零保存").isEqualTo(1);
    }

    @Test
    @DisplayName("构造：依赖不可为空")
    void dependenciesRequired() {
        assertThatThrownBy(() -> new EscrowChainReconciler(null, null, null, null))
                .isInstanceOf(com.tg.escrow.common.TggException.class);
    }
}
