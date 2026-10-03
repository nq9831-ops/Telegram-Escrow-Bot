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
import java.time.Instant;
import java.util.EnumSet;

/**
 * 交易服务——「发起一笔新交易」与「推进已有订单生命周期」的唯一入口，
 * 也是 {@link TradeAdmissionGate} 的调用方。
 *
 * <h2>生命周期推进（Wave 1 接线）</h2>
 * <p>状态机的 {@code markX} 早已就位却无人调用，订单建出来就停在 {@code CONFIRMED}。
 * 本类把「谁能把订单推到哪一步」收口成方法：{@link #lock} / {@link #deliver} /
 * {@link #release} / {@link #refund} / {@link #dispute}（外加既有的 {@link #cancel}）。
 * 权限守卫在此；状态合法性交给订单自己的 {@code markX}——<b>不重复实现第二套判定</b>，
 * 免得两处规则漂移。
 *
 * <p><b>诚实边界</b>：这些方法只推进<b>状态</b>，不移动任何真实资金——链上托管属 S5 合约。
 * 故调用方在面向用户的回执里必须显式标注「链上未接入」，否则等于把状态登记谎报成资金动作。
 *
 * <h2>它补的是什么缺口</h2>
 * <p>{@link TradeAdmissionGate} 此前是零调用方的孤岛：T23/T24/T25 三条规则判得再对，
 * 没人调用就等于没执行。本服务把「查历史 → 过门禁 → 落单」收成一条路径，
 * 让三条规则真正约束到「发起交易」这个动作上。
 *
 * <h2>流程</h2>
 * <pre>
 * initiate(request)
 *   ├─ 0. 校验请求（资金字段 / 自交易）——事实错误，直接抛
 *   ├─ 1. 查历史 → TradeAdmissionContext
 *   ├─ 2. 过门禁 → TradeAdmissionDecision
 *   ├─ 3a. 被拒 → 返回 REJECTED（<b>绝不落单</b>）+ 原因 + 可重试时刻
 *   └─ 3b. 放行 → new EscrowOrder(OPEN) → 落单 → 返回 CREATED
 * </pre>
 *
 * <h2>失败方向一律指向「不创建」</h2>
 * <ul>
 *   <li>历史查不清 → 异常原样上抛，<b>不落单</b>（查不清就不该放行）；</li>
 *   <li>落单失败 → 异常原样上抛，<b>不谎报成功</b>（{@link TradeInitiationResult}
 *       的自洽校验也会挡下「CREATED 无订单」）；</li>
 *   <li>历史归属错位（返回了别人的记录）→ 构造期即抛，避免按他人情况放行。</li>
 * </ul>
 *
 * <p>时钟由构造注入，使冷却期判定可测、且同一路径内时刻一致。
 */
public final class EscrowTradeService {

    private final TradeAdmissionGate gate;
    private final TradeHistoryPort historyPort;
    private final EscrowOrderStore orderStore;
    /**
     * 本部署允许受理的币种——「允许什么」的配置侧。
     * 与 {@link TradeCurrency}（「能不能」的能力白名单）分工见 {@link AllowedCurrencies}。
     */
    private final AllowedCurrencies allowedCurrencies;
    private final Clock clock;
    /**
     * 惩罚查询（ET-61，可选）：{@code null} = 不施加任何惩罚（既有部署行为逐字不变）。
     * 装配层给出后，准入判定会把该发起人的惩罚维度计入（{@code TRADE_LIMIT}/{@code COOLDOWN_EXTEND}）。
     */
    private final PenaltyQuery penaltyQuery;

    public EscrowTradeService(TradeAdmissionGate gate, TradeHistoryPort historyPort,
                              EscrowOrderStore orderStore, AllowedCurrencies allowedCurrencies,
                              Clock clock) {
        this(gate, historyPort, orderStore, allowedCurrencies, clock, null);
    }

    /** 带惩罚查询的构造（ET-61）：装配层传入后，准入判定会施加该发起人的惩罚维度。 */
    public EscrowTradeService(TradeAdmissionGate gate, TradeHistoryPort historyPort,
                              EscrowOrderStore orderStore, AllowedCurrencies allowedCurrencies,
                              Clock clock, PenaltyQuery penaltyQuery) {
        if (gate == null) {
            throw new EscrowException("交易创建：未提供准入门禁");
        }
        if (historyPort == null) {
            throw new EscrowException("交易创建：未提供历史查询端口");
        }
        if (orderStore == null) {
            throw new EscrowException("交易创建：未提供订单存储");
        }
        if (allowedCurrencies == null) {
            throw new EscrowException("交易创建：未提供币种允许集（请显式传 AllowedCurrencies）");
        }
        if (clock == null) {
            throw new EscrowException("交易创建：未提供时钟");
        }
        this.gate = gate;
        this.historyPort = historyPort;
        this.orderStore = orderStore;
        this.allowedCurrencies = allowedCurrencies;
        this.clock = clock;
        this.penaltyQuery = penaltyQuery;
    }

    /**
     * 发起一笔新交易。
     *
     * @param request 发起请求（买方 / 卖方 / 金额 / 币种）
     * @return 创建成功（含新订单）或被拒（含原因与可重试时刻）
     * @throws EscrowException 请求不合法，或历史/存储端口故障——这些情形下一律<b>不落单</b>
     */
    public TradeInitiationResult initiate(TradeInitiationRequest request) {
        if (request == null) {
            throw new EscrowException("交易创建：未提供发起请求");
        }
        // 本部署的受理范围（配置侧，见 AllowedCurrencies）——与「请求自身非法」同级：先拦，再谈业务策略。
        // request.currency() 已在 record 构造期归一为规范名，这里再过一次能力白名单是幂等的。
        allowedCurrencies.requireAllowed(TradeCurrency.requireSupported(request.currency()));

        Instant now = clock.instant();

        TradeAdmissionContext context = historyPort.snapshotOf(request.buyerId());
        if (context.subjectId() != request.buyerId()) {
            // 历史归属错位：按别人的情况放行等于门禁失效，直接失败而非猜
            throw new EscrowException("交易创建：历史归属错位——查的是 "
                    + request.buyerId() + "，返回的是 " + context.subjectId());
        }

        // 惩罚维度（ET-61）：未装配惩罚查询时为空集——判定与从前逐字一致
        EnumSet<PenaltyPlanner.Penalty> penalties = penaltyQuery == null
                ? EnumSet.noneOf(PenaltyPlanner.Penalty.class)
                : penaltyQuery.planOf(request.buyerId());
        TradeAdmissionDecision decision = gate.check(context, now, penalties);
        if (!decision.allowed()) {
            return TradeInitiationResult.rejected(decision);
        }

        EscrowOrder order = new EscrowOrder(request.buyerId(), request.sellerId(),
                request.amount(), request.currency(), now);
        return TradeInitiationResult.created(orderStore.save(order), decision);
    }

    /**
     * 取消订单（当事人自助撤销）。
     *
     * <p><b>权限守卫在服务层</b>：只有买方或卖方可取消，非当事人一律拒绝——命令层不该是
     * 唯一的权限防线。状态合法性交给 {@link EscrowOrder#markCancelled}（只允许 OPEN/CONFIRMED，
     * 资金已锁仓后必须走争议/退款路径，不能靠"取消"绕过）。
     *
     * @param order   已查出的订单
     * @param actorId 发起取消的用户
     * @return 已保存的订单
     * @throws EscrowException 非当事人、或订单当前状态不允许取消
     */
    public EscrowOrder cancel(EscrowOrder order, long actorId) {
        requireOrder(order, "取消");
        requireParty(order, actorId, "取消");
        order.markCancelled("当事人取消", clock.instant());
        return orderStore.save(order);
    }

    // ── 生命周期推进（Wave 1 接线：把状态机接到用户可触达的路径上）──────────

    /**
     * 买方托管登记：{@code OPEN} / {@code CONFIRMED} → {@code LOCKED}。
     *
     * <p><b>只改状态、不动钱</b>——链上托管属 S5 合约；调用方在回执里须显式标注。
     */
    public EscrowOrder lock(EscrowOrder order, long actorId) {
        requireOrder(order, "托管");
        requireBuyer(order, actorId, "托管");
        order.markLocked(clock.instant());
        return orderStore.save(order);
    }

    /** 卖方交付：{@code LOCKED} → {@code DELIVERED}。 */
    public EscrowOrder deliver(EscrowOrder order, long actorId) {
        requireOrder(order, "交付");
        requireSeller(order, actorId, "交付");
        order.markDelivered(clock.instant());
        return orderStore.save(order);
    }

    /**
     * 买方验收放款：{@code LOCKED} / {@code DELIVERED} / {@code DISPUTED} → {@code RELEASED}。
     *
     * <p><b>只改状态、不动钱</b>——真实放款属 S5 合约；调用方在回执里须显式标注。
     */
    public EscrowOrder release(EscrowOrder order, long actorId) {
        requireOrder(order, "验收放款");
        requireBuyer(order, actorId, "验收放款");
        order.markReleased(clock.instant());
        return orderStore.save(order);
    }

    /**
     * 退款：{@code LOCKED} / {@code DELIVERED} / {@code DISPUTED} → {@code REFUNDED}。
     * 买卖双方均可发起（协商退款）。
     *
     * <p><b>只改状态、不动钱</b>——真实退款属 S5 合约；调用方在回执里须显式标注。
     */
    public EscrowOrder refund(EscrowOrder order, long actorId, String reason) {
        requireOrder(order, "退款");
        requireParty(order, actorId, "退款");
        order.markRefunded(reason, clock.instant());
        return orderStore.save(order);
    }

    /**
     * 发起争议：{@code LOCKED} / {@code DELIVERED} → {@code DISPUTED}。
     *
     * <p>守卫直接复用 {@link DisputeFlow#requireCanInitiate}（状态 + 当事人）——
     * 不在服务层另写一套「谁能发起、何时能发起」，免得两处规则漂移。
     */
    public EscrowOrder dispute(EscrowOrder order, long actorId, String reason) {
        requireOrder(order, "争议");
        if (reason == null || reason.isBlank()) {
            throw new EscrowException("争议发起：理由未提供（空理由等于没记原因）");
        }
        DisputeFlow.requireCanInitiate(order, actorId);
        order.markDisputed(reason, clock.instant());
        return orderStore.save(order);
    }

    private static void requireOrder(EscrowOrder order, String action) {
        if (order == null) {
            throw new EscrowException("交易" + action + "：未提供订单");
        }
    }

    private static void requireParty(EscrowOrder order, long actorId, String action) {
        if (order.getBuyerUserId() != actorId && order.getSellerUserId() != actorId) {
            throw new EscrowException("无权" + action + "该订单：仅买卖双方可操作");
        }
    }

    private static void requireBuyer(EscrowOrder order, long actorId, String action) {
        if (order.getBuyerUserId() != actorId) {
            throw new EscrowException("无权" + action + "该订单：仅买方可操作");
        }
    }

    private static void requireSeller(EscrowOrder order, long actorId, String action) {
        if (order.getSellerUserId() != actorId) {
            throw new EscrowException("无权" + action + "该订单：仅卖方可操作");
        }
    }
}
