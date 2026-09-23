package com.tg.escrow.chain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 单个数据源对某地址余额的一次观测。
 *
 * <p><b>为什么观测要带确认数</b>：链上的"余额"不是一个瞬时事实，而是一个随最新区块推进的
 * 状态。同一地址在不同确认深度下可能给出不同答案（重组、未确认交易）。所以"读到 100"
 * 本身不构成事实，"在第 N 个确认深度上读到 100"才是。
 *
 * <p>不校验余额是否非负：链上确实可能出现异常状态（合约余额为 0、地址不存在），
 * 判定它是否合理是调用方的事，本记录只承载观测。
 */
public record BalanceObservation(String source, BigDecimal balance, int confirmations,
                                 Instant observedAt) {

    public BalanceObservation {
        if (source == null || source.isBlank()) {
            throw new ChainUnavailableException("观测缺少来源标识");
        }
        if (balance == null) {
            throw new ChainUnavailableException("观测缺少余额值（来源 " + source + "）");
        }
        if (confirmations < 0) {
            throw new ChainUnavailableException(
                    "观测的确认数为负（来源 " + source + "，值 " + confirmations + "）");
        }
        if (observedAt == null) {
            throw new ChainUnavailableException("观测缺少时刻（来源 " + source + "）");
        }
    }
}
