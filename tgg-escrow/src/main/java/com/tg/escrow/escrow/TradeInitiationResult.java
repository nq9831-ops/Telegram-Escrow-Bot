package com.tg.escrow.escrow;

import com.tg.escrow.common.EscrowException;

/**
 * 交易创建结果——<b>创建成功带订单，被拒带裁决</b>，两者互斥。
 *
 * <p>为什么不让 {@code initiate} 直接返回 {@code EscrowOrder} 或抛异常：被拒是
 * <b>正常业务结果</b>（用户该看到「为什么被拒、何时能再来」），不是异常；
 * 而把两种结局压成一个返回值又会让调用方漏判。本类型用自洽校验把「说成功却没订单」
 * 这类矛盾挡在构造期。
 *
 * @param status   结局：{@link Status#CREATED} 或 {@link Status#REJECTED}
 * @param order    创建成功时的新订单；被拒时必为 {@code null}
 * @param decision 准入裁决（被拒时携带原因与可重试时刻）
 */
public record TradeInitiationResult(Status status, EscrowOrder order,
                                    TradeAdmissionDecision decision) {

    /** 结局。 */
    public enum Status {
        /** 已创建订单。 */
        CREATED,
        /** 被准入门禁拒绝，未创建订单。 */
        REJECTED
    }

    public TradeInitiationResult {
        if (status == null) {
            throw new EscrowException("交易创建结果缺少结局状态");
        }
        if (decision == null) {
            throw new EscrowException("交易创建结果缺少准入裁决");
        }
        if (status == Status.CREATED) {
            if (order == null) {
                throw new EscrowException("CREATED 结果必须携带订单——否则等于谎报创建成功");
            }
            if (!decision.allowed()) {
                throw new EscrowException("CREATED 结果的准入裁决必须是放行，实为 " + decision.reason());
            }
        } else {
            if (order != null) {
                throw new EscrowException("REJECTED 结果不得携带订单——被拒即未落单");
            }
            if (decision.allowed()) {
                throw new EscrowException("REJECTED 结果的准入裁决必须是拒绝");
            }
        }
    }

    /**
     * 创建成功。
     *
     * @param order    已落单订单（不得为 {@code null}）
     * @param decision 放行裁决
     */
    public static TradeInitiationResult created(EscrowOrder order, TradeAdmissionDecision decision) {
        return new TradeInitiationResult(Status.CREATED, order, decision);
    }

    /**
     * 被拒绝（未落单）。
     *
     * @param decision 拒绝裁决（携带原因与可重试时刻）
     */
    public static TradeInitiationResult rejected(TradeAdmissionDecision decision) {
        return new TradeInitiationResult(Status.REJECTED, null, decision);
    }
}
