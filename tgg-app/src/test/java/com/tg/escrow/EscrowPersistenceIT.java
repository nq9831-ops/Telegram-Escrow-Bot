package com.tg.escrow;

import com.tg.escrow.escrow.EscrowOrder;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
 * <p>需要一个可达的 MySQL 实例与已建好的库（见 {@code application.yml} 的环境变量）。
 * 由 failsafe 在 {@code mvn verify} 阶段执行；{@code mvn test} 不会跑它
 * （默认 surefire 只认 {@code *Test}）——这个区分是刻意的：单元测试永远快，
 * 集成测试需要环境。
 */
@SpringBootTest
class EscrowPersistenceIT {

    @Autowired
    private EntityManager entityManager;

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
}
