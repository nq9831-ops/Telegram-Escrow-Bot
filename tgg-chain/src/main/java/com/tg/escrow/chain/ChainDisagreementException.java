package com.tg.escrow.chain;

import com.tg.escrow.common.TggException;

/**
 * 多个链上数据源对同一事实给出了<b>互相矛盾</b>的结论。
 *
 * <p>这是本模块最重要的异常：它意味着至少有一个源不可信，而**在资金场景下无法判断是哪一个**。
 * 因此处置方式不是重试、不是取多数、更不是取对自己有利的那个——而是停下并告警。
 *
 * <p>为什么"取多数"是错的：若两个源来自同一家云厂商或同一段网络，它们可能一起出错；
 * 而"三源里两票通过"会让一次孤立的分歧被静默吞掉。分歧本身就是要人看的信息。
 */
public class ChainDisagreementException extends TggException {

    private static final long serialVersionUID = 1L;

    public ChainDisagreementException(String message) {
        super(message);
    }

    public ChainDisagreementException(String message, Throwable cause) {
        super(message, cause);
    }
}
