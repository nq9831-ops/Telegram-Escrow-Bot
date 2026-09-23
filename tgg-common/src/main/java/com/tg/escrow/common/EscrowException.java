package com.tg.escrow.common;

/**
 * 担保交易领域的业务异常基类。
 *
 * <p>区别于通用的 {@link TggException}：本异常专指担保交易领域的失败
 * （状态迁移非法、订单数据异常等），使上层能把「担保领域问题」单独挑出来处理。
 *
 * <p>继承 {@link TggException} 而非受检异常：状态迁移违规是<b>不该被 catch 后继续</b>的
 * 情形——静默吞掉它等于让一笔资金停在未知状态。
 */
public class EscrowException extends TggException {

    private static final long serialVersionUID = 1L;

    public EscrowException(String message) {
        super(message);
    }

    public EscrowException(String message, Throwable cause) {
        super(message, cause);
    }
}
