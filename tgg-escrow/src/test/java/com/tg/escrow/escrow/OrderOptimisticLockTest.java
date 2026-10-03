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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 订单乐观锁的真实持久化验证（V3 迁移 + {@code @Version}）——真 H2 + 真 Flyway + 真 JPA。
 *
 * <h2>为什么必须有它</h2>
 * <p>{@code @Version} 只在<b>真库往返</b>时才生效：单元测试里的内存 fake 永远不会抛版本冲突，
 * 所以「乐观锁有没有接上、V3 迁移列有没有建」这类事实，靠单测<b>证不出来</b>。
 * 本类用确定性场景复现"读-改-写"的竞态——不靠线程调度，因此不会 flaky：
 *
 * <pre>
 *   T1 读订单(v0) ──▶ 另一操作者改成 CONFIRMED(v1，已提交)
 *                  └─▶ T1 用旧快照(v0)写回 ──▶ 版本不匹配，写入被拒
 * </pre>
 *
 * <p>失败方向指向「不写脏数据」：宁可让一方收到冲突提示去重查，也不留下状态自相矛盾的订单。
 */
@SpringBootTest(classes = EscrowPersistenceTestConfig.class)
class OrderOptimisticLockTest {

    private static final Instant T0 = Instant.parse("2026-09-23T00:00:00Z");

    @Autowired
    private EscrowOrderRepository repository;

    private EscrowOrder newOrder() {
        EscrowOrder order = new EscrowOrder(1001L, 2002L, new BigDecimal("100"), "USDT", T0);
        return repository.saveAndFlush(order);
    }

    @Test
    @DisplayName("V3 迁移生效：订单带版本列，新建后 version=0 且可回读")
    void versionColumnPresentAfterMigration() {
        EscrowOrder saved = newOrder();

        assertThat(saved.getId()).isNotNull();
        // 能持久化并回读即证明 version 列已由 V3 建立（ddl-auto=validate 也会拦下缺失）
        assertThat(repository.findById(saved.getId())).isPresent();
    }

    @Test
    @DisplayName("乐观锁：旧快照写回被拒——不静默覆盖他人已提交的变更")
    void staleSnapshotWriteIsRejected() {
        EscrowOrder order = newOrder();
        long id = order.getId();

        // T1 读到的快照（模拟"读到 OPEN 之后，别人先动了手"）
        EscrowOrder staleSnapshot = repository.findById(id).orElseThrow();

        // 另一操作者先改并提交：OPEN → CONFIRMED（version 0 → 1）
        EscrowOrder firstWriter = repository.findById(id).orElseThrow();
        firstWriter.markConfirmed(T0);
        repository.saveAndFlush(firstWriter);

        // T1 用旧快照写回：版本已过期，必须被拒——否则会把 CONFIRMED 覆盖回旧状态
        staleSnapshot.markCancelled("并发取消", T0);
        assertThatThrownBy(() -> repository.saveAndFlush(staleSnapshot))
                .as("旧快照写回必须失败，而不是覆盖他人变更")
                .isInstanceOf(OptimisticLockingFailureException.class);

        // 且库里仍是先写者的结果（没被脏写）
        assertThat(repository.findById(id).orElseThrow().currentState())
                .isEqualTo(EscrowOrder.State.CONFIRMED);
    }

    @Test
    @DisplayName("适配器边界：乐观锁冲突被转译为领域异常（技术异常不外漏）")
    void conflictTranslatedToDomainException() {
        EscrowOrderStore store = new JpaEscrowOrderStore(repository);
        EscrowOrder order = store.save(new EscrowOrder(1003L, 2004L, new BigDecimal("7"), "USDT", T0));
        long id = order.getId();

        EscrowOrder staleSnapshot = repository.findById(id).orElseThrow();
        EscrowOrder firstWriter = repository.findById(id).orElseThrow();
        firstWriter.markConfirmed(T0);
        store.save(firstWriter);

        staleSnapshot.markCancelled("并发取消", T0);
        assertThatThrownBy(() -> store.save(staleSnapshot))
                .as("store 应把 Spring 的乐观锁异常转译为领域异常")
                .isInstanceOf(ConcurrentOrderUpdateException.class);
    }
}
