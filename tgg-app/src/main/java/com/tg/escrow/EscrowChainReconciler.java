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
import com.tg.escrow.common.TggException;
import com.tg.escrow.escrow.EscrowOrder;
import com.tg.escrow.escrow.EscrowOrder.State;
import com.tg.escrow.escrow.EscrowOrderLookupPort;
import com.tg.escrow.escrow.EscrowOrderStore;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 链上状态对账（ET-19 步 2）——把「链上资金事实」与「链下流程登记」摆在一起，按保守规则行动。
 *
 * <h2>语义前提：链下是流程登记，不是链上镜像</h2>
 * <p>链下状态可以<b>领先</b>链上（命令流先登记、用户稍后走链上），这是常态而非异常。
 * 对账只处理两个方向的不一致：
 * <ul>
 *   <li><b>链上领先 → 按序回填</b>：链上发生了资金动作（用户钱包路径）而链下没跟上——
 *       按域状态机的<b>合法转移序列逐跳 mark</b>（每跳都过域守卫），最后保存一次。
 *       回填是「登记事实」不是「推进业务」：每一步都由链上已发生的事实背书。</li>
 *   <li><b>危险不一致 → 只报告不动作</b>：链下已登记终态（钱走了）而链上资金仍在合约，
 *       或两个终态方向相反——自动改任何一侧都是错，需人工核对。</li>
 * </ul>
 *
 * <h2>刻意不做的事</h2>
 * <p>不写链（对账是只读 + 本地登记校正）、不发通知、不改「链下流程领先」的正常态、
 * 不猜链上路径未覆盖的行为（链下终态而链上 OPEN = 命令流独立完成，记信息级）。
 */
public final class EscrowChainReconciler {

    /** 对账判定。 */
    public enum Verdict {
        /** 一致（含链下流程领先的正常态）。 */
        CONSISTENT,
        /** 链上领先——已按序回填链下。 */
        BACKFILLED,
        /** 危险不一致——只报告（资金与登记相反，或资金滞留合约）。 */
        DANGER,
        /** 链上路径未被使用（链下已终态而链上 OPEN）——信息级。 */
        CHAIN_UNUSED,
        /** 订单未部署链上合约——对账只覆盖链上路径。 */
        NOT_DEPLOYED,
        /** 订单不存在。 */
        NOT_FOUND,
        /** 链上查询失败——如实回报，未动作。 */
        CHAIN_UNAVAILABLE
    }

    /**
     * 对账结果。
     *
     * @param orderId    订单
     * @param chainState 链上状态（查询失败/未部署时为 {@code null}）
     * @param orderState 对账后链下状态
     * @param verdict    判定
     * @param actions    已执行的回填动作（域方法名序列）
     * @param note       说明（危险/跳过原因等）
     */
    public record Result(long orderId, Integer chainState, String orderState, Verdict verdict,
                         List<String> actions, String note) {
    }

    private final EscrowOrderLookupPort orders;
    private final EscrowOrderStore store;
    private final ChainGateway chain;
    private final Clock clock;

    public EscrowChainReconciler(EscrowOrderLookupPort orders, EscrowOrderStore store,
                                 ChainGateway chain, Clock clock) {
        if (orders == null || store == null || chain == null || clock == null) {
            throw new TggException("链上对账：订单查询、订单存储、链网关与时钟均不可为空");
        }
        this.orders = orders;
        this.store = store;
        this.chain = chain;
        this.clock = clock;
    }

    /** 对账单笔订单（幂等：重复对账同结果——一致时零动作）。 */
    public Result reconcile(long orderId) {
        EscrowOrder order = orders.byId(orderId).orElse(null);
        if (order == null) {
            return new Result(orderId, null, null, Verdict.NOT_FOUND, List.of(), "订单不存在");
        }
        State current = order.currentState();
        String address = order.getChainContractAddress();
        if (address == null || address.isBlank()) {
            return new Result(orderId, null, current.name(), Verdict.NOT_DEPLOYED, List.of(),
                    "订单未部署链上合约——对账只覆盖链上路径");
        }
        int chainState;
        try {
            chainState = chain.escrowStateOf(address);
        } catch (ChainUnavailableException ex) {
            return new Result(orderId, null, current.name(), Verdict.CHAIN_UNAVAILABLE, List.of(),
                    ex.getMessage());
        }

        String danger = dangerNote(current, chainState);
        if (danger != null) {
            return new Result(orderId, chainState, current.name(), Verdict.DANGER, List.of(), danger);
        }

        List<String> actions = new ArrayList<>();
        backfill(order, current, chainState, actions, clock.instant());
        if (!actions.isEmpty()) {
            store.save(order);
            return new Result(orderId, chainState, order.getState(), Verdict.BACKFILLED,
                    List.copyOf(actions), null);
        }
        if (chainState == 0 && (current == State.RELEASED || current == State.REFUNDED)) {
            return new Result(orderId, chainState, current.name(), Verdict.CHAIN_UNUSED, List.of(),
                    "链下已按命令流完成（链上路径未被使用）");
        }
        return new Result(orderId, chainState, current.name(), Verdict.CONSISTENT, List.of(), null);
    }

    /**
     * 按链上目标态补跳域状态机——每跳都走域守卫，序列对应合约状态机的必经路径。
     * 例：链上 RELEASED 而链下 LOCKED → [markDelivered, markReleased]（合约 Confirm 的前态是 DELIVERED）。
     */
    private static void backfill(EscrowOrder order, State current, int chainState,
                                 List<String> actions, Instant now) {
        State s = current;
        if (chainState >= 1 && (s == State.OPEN || s == State.CONFIRMED)) {
            order.markLocked(now);
            actions.add("markLocked");
            s = State.LOCKED;
        }
        if ((chainState == 2 || chainState == 4) && s == State.LOCKED) {
            order.markDelivered(now);
            actions.add("markDelivered");
            s = State.DELIVERED;
        }
        if (chainState == 3 && (s == State.LOCKED || s == State.DELIVERED)) {
            order.markDisputed("链上对账回填（合约已进入争议）", now);
            actions.add("markDisputed");
            s = State.DISPUTED;
        }
        if (chainState == 4 && (s == State.DELIVERED || s == State.DISPUTED)) {
            order.markReleased(now);
            actions.add("markReleased");
            s = State.RELEASED;
        }
        if (chainState == 5 && (s == State.LOCKED || s == State.DELIVERED || s == State.DISPUTED)) {
            order.markRefunded("链上对账回填（合约已退款）", now);
            actions.add("markRefunded");
        }
    }

    /** 危险判定：返回说明文本（危险）；{@code null} = 不危险。 */
    private static String dangerNote(State current, int chainState) {
        boolean finalOrder = current == State.RELEASED || current == State.REFUNDED
                || current == State.CANCELLED;
        boolean chainHoldsFunds = chainState >= 1 && chainState <= 3;   // LOCKED/DELIVERED/DISPUTED
        if (finalOrder && chainHoldsFunds) {
            return "链下已登记终态（" + current + "）但链上资金仍在合约（state=" + chainState
                    + "）——自动改任何一侧都是错，需人工核对";
        }
        if ((current == State.RELEASED && chainState == 5)
                || (current == State.REFUNDED && chainState == 4)) {
            return "链上与链下的资金方向相反（链下 " + current + " vs 链上 state=" + chainState
                    + "）——需人工核对";
        }
        if (current == State.CANCELLED && chainState >= 4) {
            return "链下已取消但链上已完成（state=" + chainState + "）——需人工核对";
        }
        return null;
    }
}
