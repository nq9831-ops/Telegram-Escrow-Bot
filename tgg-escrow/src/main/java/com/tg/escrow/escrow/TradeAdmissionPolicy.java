package com.tg.escrow.escrow;

import com.tg.escrow.common.EscrowException;

import java.time.Duration;

/**
 * 交易准入策略（原文档第六部分核心规则 <b>T23 单笔限制</b> / <b>T24 单笔冷却期</b>）。
 *
 * <p>数值全部由部署者给定——代码不内置业务默认值。本类只负责<b>构造期校验</b>，
 * 把「并发上限写成 0」这种会令门禁永久拒绝一切交易的配置，拦在启动前而不是运行中。
 *
 * @param maxConcurrentTrades 同一发起人同时进行中的交易上限（≥ 1）
 * @param cooldown            交易完成后的冷却期（非负；{@link Duration#ZERO} 表示不冷却）
 */
public record TradeAdmissionPolicy(int maxConcurrentTrades, Duration cooldown) {

    public TradeAdmissionPolicy {
        if (maxConcurrentTrades < 1) {
            throw new EscrowException("交易准入：并发上限必须 ≥ 1，实为 " + maxConcurrentTrades
                    + "——上限为 0 会让门禁永久拒绝一切交易");
        }
        if (cooldown == null) {
            throw new EscrowException("交易准入：冷却期未提供");
        }
        if (cooldown.isNegative()) {
            throw new EscrowException("交易准入：冷却期为负（" + cooldown + "）");
        }
    }
}
