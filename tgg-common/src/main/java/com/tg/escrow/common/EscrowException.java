package com.tg.escrow.common;

/**
 * 担保交易领域的业务异常基类。
 *
 * <p><b>为什么不用 JDK 异常</b>：状态机的 fail-closed 守卫需要把「数据异常」与
 * 「调用方写错」分开。若只抛 {@link IllegalArgumentException}，上层无法与框架的
 * 参数校验错误区分，也就无法在日志里把它标成「需要人工介入」。
 *
 * <p>继承 {@link RuntimeException} 而非受检异常：状态迁移违规是<b>不该被 catch 后继续</b>的
 * 情形——静默吞掉它等于让一笔资金停在未知状态。
 */
public class EscrowException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public EscrowException(String message) {
        super(message);
    }

    public EscrowException(String message, Throwable cause) {
        super(message, cause);
    }
}
