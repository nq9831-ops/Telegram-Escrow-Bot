package com.tg.escrow.escrow;

/**
 * 交易历史查询端口——为准入门禁汇总发起人的现状。
 *
 * <p>与 {@link PartySignatureVerifier} 同一手法：把「历史怎么算出来」抽成注入点，
 * 让服务与门禁可以在<b>纯本地</b>被穷举测试；真实实现（JPA 聚合、链上查询）
 * 只替换这一个端口。
 *
 * <h2>实现契约</h2>
 * <ul>
 *   <li>返回的 {@link TradeAdmissionContext} 必须是<b>该 subjectId 的</b>历史——
 *       传错人会让门禁按别人的情况放行，属资金级错误；</li>
 *   <li>查不清时<b>抛异常</b>而非返回「空历史」：空历史会被门禁解读为「无任何限制」，
 *       那等于把查询故障变成放行。</li>
 * </ul>
 */
@FunctionalInterface
public interface TradeHistoryPort {

    /**
     * 汇总该发起人的准入事实。
     *
     * @param subjectId 发起人 ID
     * @return 该发起人的现状（进行中笔数 / 最近完成时刻 / 有无未决争议）
     */
    TradeAdmissionContext snapshotOf(long subjectId);
}
