package com.tg.escrow.chain;

import com.tg.escrow.common.TggException;

/**
 * 多源结论一致，但确认深度尚未达到要求——链上最终性未到。
 *
 * <p>这是独立于另外两个异常的<b>第三种语义</b>，处置方式也不同：
 * <ul>
 *   <li>{@link ChainUnavailableException}：没读到 → 换源 / 重试；</li>
 *   <li>{@link ChainDisagreementException}：读到了但矛盾 → 告警、人工介入，重试无用；</li>
 *   <li>{@link ChainNotFinalizedException}：读到了且一致，但还不够深 → <b>等待后重试</b>，
 *       既不必换源，也不必告警。</li>
 * </ul>
 *
 * <p>把它与"分歧"混为一谈会导致运维误判：一次正常的"等待确认"会被报成数据源异常，
 * 久而久之值班的人就会开始忽略真告警。
 */
public class ChainNotFinalizedException extends TggException {

    private static final long serialVersionUID = 1L;

    public ChainNotFinalizedException(String message) {
        super(message);
    }

    public ChainNotFinalizedException(String message, Throwable cause) {
        super(message, cause);
    }
}
