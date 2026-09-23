package com.tg.escrow.common;

/**
 * 项目级异常基类。
 *
 * <p>放在 {@code tgg-common} 是为了让各模块共享同一棵异常树：上层可以只 catch 一次
 * 就区分出"本系统的业务失败"与"框架/第三方故障"。
 *
 * <p><b>为什么不直接用 JDK 异常</b>：状态机与配置解析都需要 fail-closed，
 * 而 {@link IllegalArgumentException} 既可能是调用方写错、也可能是数据被篡改，
 * 混在一起后无法在日志与告警里区分对待。
 *
 * <p>非抽象：允许直接抛出（例如状态机守卫这类不属于任何更具体子类的场景）。
 */
public class TggException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public TggException(String message) {
        super(message);
    }

    public TggException(String message, Throwable cause) {
        super(message, cause);
    }
}
