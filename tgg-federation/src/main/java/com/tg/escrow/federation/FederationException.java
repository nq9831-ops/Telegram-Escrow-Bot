package com.tg.escrow.federation;

import com.tg.escrow.common.TggException;

/**
 * 联邦模块异常：密钥或签名材料的格式问题。
 *
 * <p>与 {@code EscrowException} 的区别：本异常指<b>信任材料的格式或来源</b>问题
 * （seed 长度不对、公钥串非法），而不是交易状态问题。分开的原因是可运维性——
 * 前者通常意味着配置注入错误，后者意味着业务流程被非法推进。
 */
public class FederationException extends TggException {

    private static final long serialVersionUID = 1L;

    public FederationException(String message) {
        super(message);
    }

    public FederationException(String message, Throwable cause) {
        super(message, cause);
    }
}
