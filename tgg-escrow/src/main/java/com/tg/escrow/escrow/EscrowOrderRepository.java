package com.tg.escrow.escrow;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;

/**
 * 担保订单的 Spring Data 仓储。
 *
 * <p>查询方法都按「用户 + 状态集合」聚合——这正是准入门禁 T23/T24/T25 三项判定
 * 各自需要的形状。把聚合下推到数据库而非读全表再内存过滤：买家订单多了，
 * 后者会让每次准入判定都变成全表扫描。
 */
public interface EscrowOrderRepository extends JpaRepository<EscrowOrder, Long> {

    /**
     * 统计某用户（买方或卖方任一侧）处于指定状态集合内的订单数。
     *
     * @param userId 用户 ID（买方或卖方）
     * @param states 状态名集合
     */
    @Query("""
            select count(o) from EscrowOrder o
            where (o.buyerUserId = :userId or o.sellerUserId = :userId)
              and o.state in :states
            """)
    long countByUserAndStates(@Param("userId") long userId,
                              @Param("states") Collection<String> states);

    /**
     * 取某用户处于指定状态集合内订单的最大更新时刻；无匹配返回 {@code null}。
     *
     * <p>用于 T24：终态订单的 {@code updatedAt} 即「完成时刻」。
     */
    @Query("""
            select max(o.updatedAt) from EscrowOrder o
            where (o.buyerUserId = :userId or o.sellerUserId = :userId)
              and o.state in :states
            """)
    Instant findMaxUpdatedAtByUserAndStates(@Param("userId") long userId,
                                            @Param("states") Collection<String> states);
}
