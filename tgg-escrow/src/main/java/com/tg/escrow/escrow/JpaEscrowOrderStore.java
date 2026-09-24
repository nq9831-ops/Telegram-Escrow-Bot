package com.tg.escrow.escrow;

import com.tg.escrow.common.EscrowException;

import org.springframework.dao.OptimisticLockingFailureException;

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
        EscrowOrder saved;
        try {
            saved = repository.save(order);
        } catch (OptimisticLockingFailureException ex) {
            // 乐观锁冲突：本次读到的版本已被他人改过。在适配器边界转译成领域异常——
            // 技术异常不该流入领域层，且上层要能据此回"请重查"而非"操作非法"。
            throw new ConcurrentOrderUpdateException(
                    "订单已被他人变更（并发写入冲突），请重查后重试", ex);
        }
        if (saved == null) {
            // Spring Data 正常不会返回 null；此处是契约防线——落单结果未知时不谎报成功
            throw new EscrowException("订单存储：保存返回 null，落单结果未知");
        }
        return saved;
    }
}
