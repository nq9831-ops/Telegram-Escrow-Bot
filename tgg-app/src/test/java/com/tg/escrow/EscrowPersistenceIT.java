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
package com.tg.escrow;

import com.tg.escrow.escrow.EscrowOrder;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 装配级集成测试——验证"这些东西装起来能用"，而不是某个方法算得对。
 *
 * <h2>为什么必须有它</h2>
 * <p>此前全仓有 87 项单元测试、零项集成测试：所有测试都刻意<b>不启动 Spring 上下文</b>
 * （那是优点——快且无依赖），代价是"装配正确性"完全靠人肉启动一次来确认。
 * 而 POM 里 failsafe 的注释恰恰写着「没有它，{@code *IT} 会被静默跳过」——
 * 那句话此前描述的是一个空集合。
 *
 * <p>本类补上三类断言：
 * <ol>
 *   <li><b>上下文能起来</b>——bean 装配、配置绑定；</li>
 *   <li><b>迁移与实体一致</b>——{@code ddl-auto=validate} 通过（结构漂移在此暴露）；</li>
 *   <li><b>映射正确</b>——{@code Instant} / {@code BigDecimal} / 状态字符串经真库往返无损。</li>
 * </ol>
 *
 * <h2>运行前提</h2>
 * <p>需要两样外部条件，缺一样上下文就起不来：
 * <ol>
 *   <li><b>一个可达的 MySQL，且其库能通过 Flyway 校验</b>（见 {@code application.yml} 的环境变量）。
 *       注意：<b>若该库是在某个迁移脚本被修改之前建的，Flyway 会因 checksum 不匹配而拒绝启动</b>
 *       （实测：V1 的 {@code applied -951161179} vs {@code resolved 801189638}）。此时要么对该库跑
 *       {@code flyway repair}，要么换一个空库——{@code TGG_DB_NAME=<新库名>} 即可从零迁移。</li>
 *   <li><b>一个非空的 {@code TELEGRAM_BOT_TOKEN}</b>：{@code BotTokenConfig} 在 <b>bean 创建期</b>
 *       就校验非空并非空白，缺失会让上下文直接起不来——它比本类的 {@code @MockitoBean BotRunner}
 *       更早发生，所以 mock 拦不住。本 IT 不连 Telegram，故<b>值可以是任意假的</b>。</li>
 * </ol>
 *
 * <p>实测可用的完整命令（本机）：
 * <pre>{@code
 * TELEGRAM_BOT_TOKEN=123456:dummy-for-it TGG_DB_NAME=tgg_escrow_verify mvn -B verify
 * }</pre>
 *
 * <p>由 failsafe 在 {@code mvn verify} 阶段执行；{@code mvn test} 不会跑它
 * （默认 surefire 只认 {@code *Test}）——这个区分是刻意的：单元测试永远快，
 * 集成测试需要环境。
 */
// tgg.bot.username 是必备键（缺配时 InviteLink 构造即抛、上下文起不来）——IT 对外部环境
// 的最小要求保持为「MySQL + 非空 token」两项，故在测试内自带该属性，不从环境借。
@SpringBootTest(properties = "tgg.bot.username=it-test-bot")
class EscrowPersistenceIT {

    @Autowired
    private EntityManager entityManager;

    /**
     * 屏蔽常驻长轮询：{@link BotRunner} 是 {@code SmartLifecycle}，上下文启动时会用 token
     * 去连真实 Telegram——无真实凭证即 401 抛错、整个上下文起不来。本 IT 验证的是装配、
     * 迁移与映射，<b>不测 Telegram 连接</b>，故用替身拦截这一步（否则该 IT 只能挂真 token 跑）。
     */
    @MockitoBean
    private BotRunner botRunner;

    /**
     * 同 {@code BotRunner}：{@link BotMenuRegistrar} 也是 {@code SmartLifecycle}，上下文启动即
     * 调 Telegram 的 {@code setMyCommands}——用替身拦住这步真实网络调用（本 IT 不测 Telegram 连接）。
     */
    @MockitoBean
    private BotMenuRegistrar botMenuRegistrar;

    @Test
    @DisplayName("上下文启动即完成 Flyway 迁移与 JPA 结构校验")
    void contextStartsWithValidatedSchema() {
        // 这条断言看起来"什么都没验"，但它的意义在于：若实体与 escrow_orders 结构漂移，
        // 或迁移未被执行，Spring 在【上下文初始化阶段】就会失败——本方法根本进不来。
        // 换句话说，能跑到这一行本身就是结论。
        assertThat(entityManager).isNotNull();
        assertThat(entityManager.getMetamodel().getEntities())
                .as("担保订单实体应已被 JPA 识别")
                .anyMatch(e -> EscrowOrder.class.equals(e.getJavaType()));
    }

    @Test
    @Transactional
    @DisplayName("订单经真库往返无损——Instant / BigDecimal / 状态字符串的映射正确")
    void orderRoundTripsThroughRealDatabase() {
        Instant createdAt = Instant.parse("2026-09-23T10:00:00Z");
        EscrowOrder order = new EscrowOrder(
                1001L, 2002L, new BigDecimal("12.34000000"), "USDT", createdAt);

        entityManager.persist(order);
        entityManager.flush();
        Long id = order.getId();
        assertThat(id).as("自增主键应已回填").isNotNull();
        entityManager.clear();

        EscrowOrder reloaded = entityManager.find(EscrowOrder.class, id);
        assertThat(reloaded).isNotNull();
        assertThat(reloaded.currentState()).isEqualTo(EscrowOrder.State.OPEN);
        assertThat(reloaded.getBuyerUserId()).isEqualTo(1001L);
        assertThat(reloaded.getSellerUserId()).isEqualTo(2002L);
        assertThat(reloaded.getAmount())
                .as("金额经 DECIMAL(24,8) 往返后数值不变")
                .isEqualByComparingTo("12.34000000");
        assertThat(reloaded.getCurrency()).isEqualTo("USDT");
        assertThat(reloaded.getCreatedAt()).isEqualTo(createdAt);
    }

    @Test
    @Transactional
    @DisplayName("链上合约地址经真库往返——V10 列未部署为空、attach 后持久化")
    void chainContractAddressRoundTrips() {
        EscrowOrder order = new EscrowOrder(
                1001L, 2002L, new BigDecimal("1.00000000"), "TON",
                Instant.parse("2026-10-02T00:00:00Z"));
        entityManager.persist(order);
        entityManager.flush();
        Long id = order.getId();

        // 未部署时为空（V10 列可空——既有行行为不变）
        entityManager.clear();
        assertThat(entityManager.find(EscrowOrder.class, id).getChainContractAddress())
                .as("未部署订单的链上地址应为 null").isNull();

        // attach 后往返（V10 列）
        EscrowOrder reloaded = entityManager.find(EscrowOrder.class, id);
        reloaded.attachChainContractAddress(
                "EQCxE6mUtQJKFnGfaROTKOt1lZbDiiX1kCixRv7Nw2Id_sDs",
                Instant.parse("2026-10-02T00:00:01Z"));
        entityManager.flush();
        entityManager.clear();

        assertThat(entityManager.find(EscrowOrder.class, id).getChainContractAddress())
                .isEqualTo("EQCxE6mUtQJKFnGfaROTKOt1lZbDiiX1kCixRv7Nw2Id_sDs");
    }

    @Test
    @Transactional
    @DisplayName("状态推进可持久化——写回后再次读取仍是新状态")
    void stateTransitionIsPersisted() {
        EscrowOrder order = new EscrowOrder(
                2001L, 2002L, new BigDecimal("5"), "USDT", Instant.parse("2026-09-23T10:00:00Z"));
        entityManager.persist(order);
        entityManager.flush();
        Long id = order.getId();

        order.markLocked(Instant.parse("2026-09-23T11:00:00Z"));
        entityManager.flush();
        entityManager.clear();

        assertThat(entityManager.find(EscrowOrder.class, id).currentState())
                .isEqualTo(EscrowOrder.State.LOCKED);
    }

    @Test
    @Transactional
    @DisplayName("从库中读到未知状态值时抛 EscrowException——fail-closed 在真库路径上同样成立")
    void unknownStateFromDatabaseFailsClosed() {
        EscrowOrder order = new EscrowOrder(
                3001L, 3002L, new BigDecimal("1"), "USDT", Instant.parse("2026-09-23T10:00:00Z"));
        entityManager.persist(order);
        entityManager.flush();
        Long id = order.getId();

        // 绕过实体直接篡改数据库里的状态值（模拟数据被改 / 版本漂移）
        entityManager.createNativeQuery("UPDATE escrow_orders SET state = 'BOGUS' WHERE id = :id")
                .setParameter("id", id)
                .executeUpdate();
        entityManager.flush();
        entityManager.clear();

        EscrowOrder corrupted = entityManager.find(EscrowOrder.class, id);
        assertThat(corrupted.getState()).isEqualTo("BOGUS");
        org.assertj.core.api.Assertions.assertThatThrownBy(corrupted::currentState)
                .isInstanceOf(com.tg.escrow.common.EscrowException.class)
                .hasMessageContaining("BOGUS");
    }

    @Test
    @Transactional
    @DisplayName("V11 往返：链上金额（30 位大数字符串）与买卖双方 TON 地址")
    void v11ChainAmountAndTonAddressesRoundTrip() {
        EscrowOrder order = new EscrowOrder(3003L, 4004L,
                new BigDecimal("2.00000000"), "TON",
                Instant.parse("2026-10-02T00:00:00Z"));
        entityManager.persist(order);
        entityManager.flush();
        Long id = order.getId();
        entityManager.clear();

        EscrowOrder fresh = entityManager.find(EscrowOrder.class, id);
        assertThat(fresh.getChainAmountNano()).as("新列可空").isNull();
        fresh.attachChainAmount("123456789012345678901234567890",
                Instant.parse("2026-10-02T00:00:01Z"));
        fresh.attachBuyerTonAddress(
                "0:1111111111111111111111111111111111111111111111111111111111111111",
                Instant.parse("2026-10-02T00:00:01Z"));
        fresh.attachSellerTonAddress(
                "0:2222222222222222222222222222222222222222222222222222222222222222",
                Instant.parse("2026-10-02T00:00:01Z"));
        entityManager.flush();
        entityManager.clear();

        EscrowOrder again = entityManager.find(EscrowOrder.class, id);
        assertThat(again.getChainAmountNano()).isEqualTo("123456789012345678901234567890");
        assertThat(again.getBuyerTonAddress()).startsWith("0:1111");
        assertThat(again.getSellerTonAddress()).startsWith("0:2222");
    }
}
