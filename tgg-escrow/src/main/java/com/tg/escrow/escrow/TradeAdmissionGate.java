package com.tg.escrow.escrow;

import com.tg.escrow.common.EscrowException;
import com.tg.escrow.escrow.TradeAdmissionDecision.Reason;

import java.time.Instant;

/**
 * 交易准入门禁——把原文档第六部分的三条核心规则收拢成一次判定：
 * <b>T23 单笔限制</b>、<b>T24 单笔冷却期</b>、<b>T25 争议期间冻结</b>。
 *
 * <h2>为什么必须收拢到一个入口</h2>
 * <p>这三条在文档里各占一行，但它们是同一动作的三道闸：「允许此人此刻发起新交易吗」。
 * 若拆到三个调用点各判各的，必然出现一处判了、另一处漏判——而漏判的后果是资金暴露
 * 超出预期，且事后无从追查是哪一道闸没关。收拢到一处，用组合用例穷举钉住。
 *
 * <h2>拒绝原因优先级：DISPUTE &gt; CONCURRENT &gt; COOLDOWN</h2>
 * <p>多条同时触发时只回<b>首个</b>原因。争议未决是无期限的、需人工了结，最先告知
 * 能让用户直接找对入口；冷却期有时限、可自愈，排最后不影响结果但会让用户多等一轮。
 *
 * <h2>保守原则</h2>
 * <p>凡数据异常（负计数、缺失输入）一律<b>不放行</b>；完成时刻落在未来（时钟偏移）
 * 时按「冷却期仍在」处理。<b>门禁的失败方向永远指向拒绝</b>——宁可误拦一次
 * 让用户重试，不可误放一笔让资金暴露。
 */
public final class TradeAdmissionGate {

    private final TradeAdmissionPolicy policy;

    public TradeAdmissionGate(TradeAdmissionPolicy policy) {
        if (policy == null) {
            throw new EscrowException("交易准入：策略未提供");
        }
        this.policy = policy;
    }

    /**
     * 判定某发起人此刻能否发起新交易。
     *
     * @param context 发起人现状（事实，不含策略数值）
     * @param now     判定时刻——由调用方注入，便于测试与统一时钟源
     * @return 裁决（含原因与可重试时刻）
     * @throws EscrowException 输入缺失或数据异常——<b>不放行</b>而非静默放行
     */
    public TradeAdmissionDecision check(TradeAdmissionContext context, Instant now) {
        if (context == null) {
            throw new EscrowException("交易准入：未提供发起人现状");
        }
        if (now == null) {
            throw new EscrowException("交易准入：未提供判定时刻");
        }

        // T25：争议期间冻结——无期限，优先告知
        if (context.hasUnresolvedDispute()) {
            return TradeAdmissionDecision.reject(Reason.DISPUTE_HOLD, null);
        }

        // T23：单笔限制——已达上限即拒（「最多 N 笔」是含 N 的）
        if (context.activeTradeCount() >= policy.maxConcurrentTrades()) {
            return TradeAdmissionDecision.reject(Reason.CONCURRENT_LIMIT, null);
        }

        // T24：单笔冷却期——「24 小时内」不含整点，故恰好满期放行。
        // 完成时刻落在未来时 retryAfter 仍在前方，自然按冷却中拒绝，无需特判。
        if (context.lastCompletedTradeAt() != null) {
            Instant retryAfter = context.lastCompletedTradeAt().plus(policy.cooldown());
            if (now.isBefore(retryAfter)) {
                return TradeAdmissionDecision.reject(Reason.COOLDOWN, retryAfter);
            }
        }

        return TradeAdmissionDecision.allow();
    }
}
