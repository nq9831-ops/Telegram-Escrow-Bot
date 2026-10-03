/*
 * SPDX-License-Identifier: AGPL-3.0-only
 * Copyright (C) 2026 telegram-escrow-bot contributors
 *
 * This file is part of telegram-escrow-bot.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, version 3 of the License only.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along
 * with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * NOTE: the SPDX identifier is AGPL-3.0-only because the LICENSE file in this
 * repository carries the plain AGPL v3 text without an "or later" grant. If you
 * intend to allow later versions, change this line to AGPL-3.0-or-later and
 * make the LICENSE wording match — the two must not disagree.
 */
package com.tg.escrow.escrow;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * 担保订单的 Spring Data 仓储。
 *
 * <p>查询方法都按「用户 + 状态集合」聚合——这正是准入门禁 T23/T24/T25 三项判定
 * 各自需要的形状。把聚合下推到数据库而非读全表再内存过滤：买家订单多了，
 * 后者会让每次准入判定都变成全表扫描。
 *
 * <p>本仓储同时服务**信用/榜单读模型**（{@code TraderStatsPort} 的 JPA 实现）：
 * 那里需要的「完成数 / 参与总数 / 首末时刻 / 对手分布」也一律下推成聚合，
 * 理由同上——榜单是每次命令都要算的东西，不该拖回全表。
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

    // ── 信用 / 榜单读模型（ET-03/37/72/75/76）───────────────────────────────
    //
    // 刻意**不**复用上面的「按状态集合」版本来表达"全部状态"：那样每加一个新状态
    // 都要回来补一处调用点，漏了就会静默漏算。凡"全部状态"一律用下面这两条无状态条件的。

    /**
     * 某用户参与的全部订单数（买卖任一侧、无论状态）——争议率的<b>分母</b>。
     */
    @Query("""
            select count(o) from EscrowOrder o
            where o.buyerUserId = :userId or o.sellerUserId = :userId
            """)
    long countAllByUser(@Param("userId") long userId);

    /** 某用户参与订单的最早创建时刻；无订单返回 {@code null}（活跃天数的起点）。 */
    @Query("""
            select min(o.createdAt) from EscrowOrder o
            where o.buyerUserId = :userId or o.sellerUserId = :userId
            """)
    Instant findMinCreatedAtByUser(@Param("userId") long userId);

    /** 某用户参与订单的最大更新时刻；无订单返回 {@code null}（活跃天数的终点）。 */
    @Query("""
            select max(o.updatedAt) from EscrowOrder o
            where o.buyerUserId = :userId or o.sellerUserId = :userId
            """)
    Instant findMaxUpdatedAtByUser(@Param("userId") long userId);

    /**
     * 某用户<b>作为买方</b>时，各卖方（对手）的订单笔数。每行为 {@code [对手ID, 笔数]}。
     *
     * <p>拆成买方视角/卖方视角两条而不是一条 {@code case when}：JPQL 分组的关键字选择
     * 在两种查询计划下可读性差，而两条简单查询的结果在适配器里合并一目了然。
     */
    @Query("""
            select o.sellerUserId, count(o) from EscrowOrder o
            where o.buyerUserId = :userId
            group by o.sellerUserId
            """)
    List<Object[]> countBySellerForBuyer(@Param("userId") long userId);

    /** 某用户<b>作为卖方</b>时，各买方（对手）的订单笔数。每行为 {@code [对手ID, 笔数]}。 */
    @Query("""
            select o.buyerUserId, count(o) from EscrowOrder o
            where o.sellerUserId = :userId
            group by o.buyerUserId
            """)
    List<Object[]> countByBuyerForSeller(@Param("userId") long userId);

    /**
     * 所有曾有成交史的用户 ID（买卖任一侧出现即计入，去重）。
     *
     * <p>供榜单枚举"参与者"——**不是**按评分排序（库里没有分数列）。
     */
    @Query("""
            select distinct o.buyerUserId from EscrowOrder o
            """)
    List<Long> distinctBuyerIds();

    /** 所有曾作为卖方出现的用户 ID（去重）。与 {@link #distinctBuyerIds()} 合并后即全部参与者。 */
    @Query("""
            select distinct o.sellerUserId from EscrowOrder o
            """)
    List<Long> distinctSellerIds();

    /**
     * 某用户<b>最近参与</b>的订单（买卖任一侧），按最后更新时刻倒序——`/escrow my` 的唯一查询。
     *
     * <p>用 Spring Data 方法名派生 + {@link Pageable} 而不是 JPQL：JPQL 没有 {@code limit}
     * 语法，分页是标准做法（调用方传 {@code PageRequest.of(0, limit)}）。
     */
    List<EscrowOrder> findByBuyerUserIdOrSellerUserIdOrderByUpdatedAtDesc(
            long buyerUserId, long sellerUserId, Pageable pageable);
}
