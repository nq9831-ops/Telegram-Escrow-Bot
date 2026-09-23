package com.tg.escrow.escrow;

import com.tg.escrow.common.EscrowException;

import java.time.Instant;

/**
 * 发起新交易前，调用方从存储 / 链上汇总出的发起人现状。
 *
 * <p>刻意只放<b>事实</b>（几笔、什么时候、有没有争议），不放任何策略数值——
 * 阈值属 {@link TradeAdmissionPolicy}。事实与判断分开，同一份现状才能被不同策略复用。
 *
 * <p><b>角色口径</b>：原文档 T23 限定为「买方」，而 T24/T25 针对「发起人」。
 * 担保流程的发起人即买方，故本类按单一「发起人」统一建模而不损失语义；
 * 将来若支持卖方发起，需按角色拆分检查对象。
 *
 * @param subjectId            发起人 ID
 * @param activeTradeCount     该发起人当前进行中的交易笔数
 * @param lastCompletedTradeAt 最近一笔终态交易的完成时刻；无则为 {@code null}
 * @param hasUnresolvedDispute 是否存在未决争议（对应 T25）
 */
public record TradeAdmissionContext(long subjectId, int activeTradeCount,
                                    Instant lastCompletedTradeAt,
                                    boolean hasUnresolvedDispute) {

    public TradeAdmissionContext {
        if (activeTradeCount < 0) {
            // 数据异常不是业务条件：宁可抛出让上游看见，也不要把「负数」当「零笔」放行
            throw new EscrowException("交易准入：进行中笔数为负（" + activeTradeCount
                    + "）——数据异常，拒绝据此判定");
        }
    }
}
