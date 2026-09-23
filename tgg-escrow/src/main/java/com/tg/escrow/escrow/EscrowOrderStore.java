package com.tg.escrow.escrow;

/**
 * 订单存储端口——新建订单的落单出口。
 *
 * <p>抽成接口与 {@link TradeHistoryPort} 同理：落单到哪（JPA 表、内存、事件总线）
 * 与「该不该落单」是两件事，前者可替换，后者必须在纯本地被穷举测试。
 *
 * <h2>实现契约</h2>
 * <ul>
 *   <li>保存失败必须<b>抛异常</b>，不得静默返回——否则调用方会以为创建成功；</li>
 *   <li>不得返回 {@code null}：落单结果未知时，上层会因「CREATED 必须带订单」
 *       的自洽校验而失败，宁可失败也不谎报成功。</li>
 * </ul>
 */
public interface EscrowOrderStore {

    /**
     * 保存新建订单。
     *
     * @param order 待保存订单（初始态 {@code OPEN}）
     * @return 已保存订单（可回填 ID），不得为 {@code null}
     */
    EscrowOrder save(EscrowOrder order);
}
