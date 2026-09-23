package com.tg.escrow.escrow;

import com.tg.escrow.common.EscrowException;

/**
 * {@link EscrowOrderStore} 的 JPA 实现——落单到 {@code escrow_orders} 表。
 *
 * <p>守端口契约的两条：失败抛异常不静默返回，且绝不返回 {@code null}
 * （落单结果未知时宁可让上层失败，也不谎报创建成功）。
 */
public final class JpaEscrowOrderStore implements EscrowOrderStore {

    private final EscrowOrderRepository repository;

    public JpaEscrowOrderStore(EscrowOrderRepository repository) {
        if (repository == null) {
            throw new EscrowException("订单存储：未提供仓储");
        }
        this.repository = repository;
    }

    @Override
    public EscrowOrder save(EscrowOrder order) {
        if (order == null) {
            throw new EscrowException("订单存储：不得保存 null 订单");
        }
        EscrowOrder saved = repository.save(order);
        if (saved == null) {
            // Spring Data 正常不会返回 null；此处是契约防线——落单结果未知时不谎报成功
            throw new EscrowException("订单存储：保存返回 null，落单结果未知");
        }
        return saved;
    }
}
