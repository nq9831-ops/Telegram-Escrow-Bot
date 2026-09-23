package com.tg.escrow.chain;

import com.tg.escrow.common.TggException;

/**
 * 链上数据源不可用，或可用源不足以形成交叉验证。
 *
 * <p>与 {@link ChainDisagreementException} 分开的原因在于<b>应对方式不同</b>：
 * 本异常意味着"没读到"，可以重试、可以扩容数据源；而分歧意味着"读到了但互相矛盾"，
 * 重试无用，必须告警并由人介入。
 */
public class ChainUnavailableException extends TggException {

    private static final long serialVersionUID = 1L;

    public ChainUnavailableException(String message) {
        super(message);
    }

    public ChainUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
