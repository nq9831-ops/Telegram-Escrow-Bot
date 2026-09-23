package com.tg.escrow.escrow;

import com.tg.escrow.common.EscrowException;

import java.time.Clock;
import java.time.Instant;

/**
 * 交易创建服务——「发起一笔新交易」的唯一入口，也是 {@link TradeAdmissionGate} 的调用方。
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
    private final Clock clock;

    public EscrowTradeService(TradeAdmissionGate gate, TradeHistoryPort historyPort,
                              EscrowOrderStore orderStore, Clock clock) {
        if (gate == null) {
            throw new EscrowException("交易创建：未提供准入门禁");
        }
        if (historyPort == null) {
            throw new EscrowException("交易创建：未提供历史查询端口");
        }
        if (orderStore == null) {
            throw new EscrowException("交易创建：未提供订单存储");
        }
        if (clock == null) {
            throw new EscrowException("交易创建：未提供时钟");
        }
        this.gate = gate;
        this.historyPort = historyPort;
        this.orderStore = orderStore;
        this.clock = clock;
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

        Instant now = clock.instant();

        TradeAdmissionContext context = historyPort.snapshotOf(request.buyerId());
        if (context.subjectId() != request.buyerId()) {
            // 历史归属错位：按别人的情况放行等于门禁失效，直接失败而非猜
            throw new EscrowException("交易创建：历史归属错位——查的是 "
                    + request.buyerId() + "，返回的是 " + context.subjectId());
        }

        TradeAdmissionDecision decision = gate.check(context, now);
        if (!decision.allowed()) {
            return TradeInitiationResult.rejected(decision);
        }

        EscrowOrder order = new EscrowOrder(request.buyerId(), request.sellerId(),
                request.amount(), request.currency(), now);
        return TradeInitiationResult.created(orderStore.save(order), decision);
    }
}
