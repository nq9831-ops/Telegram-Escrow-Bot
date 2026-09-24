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
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 邀请并发抢接的真实持久化验证（V4 迁移 + {@code @Version}）——真 H2 + 真 Flyway + 真 JPA。
 *
 * <h2>为什么必须有它</h2>
 * <p>{@code @Version} 只在真库往返时生效；内存 fake 永远不抛版本冲突，因此「邀请的乐观锁
 * 有没有接上、V4 的 version 列有没有建」靠单测证不出来。本类用确定性场景复现"两个接受者
 * 抢同一条邀请"的竞态——不依赖线程调度，故不 flaky：
 *
 * <pre>
 *   接受者 A 读邀请(v0) ──▶ 接受者 B 先接受并提交(写 acceptedOrderId, v0→v1)
 *                        └─▶ A 用旧快照(v0)写回 ──▶ 版本不匹配，写入被拒
 * </pre>
 *
 * <p>失败方向指向「不重复接单」：宁可让后到者收到冲突提示，也不产生第二笔订单或覆盖接受者。
 *
 * <p>每个用例用<b>独立随机令牌</b>：本类为 {@code @SpringBootTest}（不随用例回滚），
 * 而令牌有唯一索引——复用同一定值会在第二个用例撞索引，那是测试造数问题而非产品缺陷。
 */
@SpringBootTest(classes = EscrowPersistenceTestConfig.class)
class TradeInviteOptimisticLockTest {

    private static final Instant T0 = Instant.parse("2026-09-24T00:00:00Z");

    @Autowired
    private TradeInviteRepository repository;

    @Autowired
    private TradeInviteStore store;

    private TradeInvite newInvite() {
        String token = UUID.randomUUID().toString().replace("-", "");
        return repository.saveAndFlush(new TradeInvite(
                1001L, new BigDecimal("100"), "USDT", token, T0, T0.plus(Duration.ofHours(24))));
    }

    @Test
    @DisplayName("V4 迁移生效：邀请带版本列，新建后 id 已回填且可按令牌回读")
    void versionColumnPresentAfterMigration() {
        TradeInvite saved = newInvite();

        assertThat(saved.getId()).isNotNull();
        assertThat(repository.findByToken(saved.getToken())).isPresent();
    }

    @Test
    @DisplayName("乐观锁：旧快照消费写回被拒——不静默覆盖他人已接受的标记")
    void staleConsumptionIsRejected() {
        TradeInvite invite = newInvite();
        long id = invite.getId();

        // 接受者 A 读到的快照
        TradeInvite staleSnapshot = repository.findById(id).orElseThrow();

        // 接受者 B 先接受并提交（v0 → v1）
        TradeInvite firstAcceptor = repository.findById(id).orElseThrow();
        firstAcceptor.markAccepted(500L);
        repository.saveAndFlush(firstAcceptor);

        staleSnapshot.markAccepted(600L);
        assertThatThrownBy(() -> repository.saveAndFlush(staleSnapshot))
                .as("旧快照写回必须失败，而不是覆盖他人已接受的邀请")
                .isInstanceOf(OptimisticLockingFailureException.class);

        assertThat(repository.findById(id).orElseThrow().getAcceptedOrderId()).isEqualTo(500L);
    }

    @Test
    @DisplayName("适配器边界：邀请乐观锁冲突被转译为领域异常（技术异常不外漏）")
    void conflictTranslatedToDomainException() {
        TradeInvite invite = newInvite();
        long id = invite.getId();

        TradeInvite staleSnapshot = repository.findById(id).orElseThrow();
        TradeInvite firstAcceptor = repository.findById(id).orElseThrow();
        firstAcceptor.markAccepted(700L);
        store.save(firstAcceptor);

        staleSnapshot.markAccepted(800L);
        assertThatThrownBy(() -> store.save(staleSnapshot))
                .as("store 应把 Spring 的乐观锁异常转译为 ConcurrentOrderUpdateException")
                .isInstanceOf(ConcurrentOrderUpdateException.class);
    }
}
