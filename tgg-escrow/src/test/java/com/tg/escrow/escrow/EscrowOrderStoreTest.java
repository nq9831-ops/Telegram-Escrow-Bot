package com.tg.escrow.escrow;

import com.tg.escrow.common.EscrowException;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ContextConfiguration;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 持久化层的行为固定测试——用 H2（MySQL 兼容模式）跑真实 JPA 映射与 Flyway 迁移。
 *
 * <p>为什么不 mock：本层的错误形态是「列名对不上 / 类型不匹配 / 迁移没跑」，
 * 这些 mock 全测不出来，只会表现成生产环境启动即失败。用真库跑一遍 DDL 与实体映射，
 * 是唯一能证明二者一致的方式。
 */
@DataJpaTest
@ContextConfiguration(classes = EscrowPersistenceTestConfig.class)
class EscrowOrderStoreTest {

    private static final Instant T0 = Instant.parse("2026-09-23T10:00:00Z");
    private static final long BUYER = 1001L;
    private static final long SELLER = 2002L;

    @Autowired
    private EscrowOrderStore store;

    @Autowired
    private TradeHistoryPort history;

    @Autowired
    private EscrowOrderRepository repository;

    @Autowired
    private EntityManager entityManager;

    /** 刷盘并清持久化上下文，强制后续读取走数据库而非一级缓存。 */
    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private EscrowOrder newOrder(long buyer, long seller, Instant at) {
        return new EscrowOrder(buyer, seller, new BigDecimal("100.00000000"), "USDT", at);
    }

    // ── 落单 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("save 后回填主键，且字段经真库往返无损")
    void savePersistsAndReturnsId() {
        EscrowOrder saved = store.save(newOrder(BUYER, SELLER, T0));

        assertThat(saved.getId()).isNotNull();

        flushAndClear();
        EscrowOrder reloaded = repository.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.getBuyerUserId()).isEqualTo(BUYER);
        assertThat(reloaded.getSellerUserId()).isEqualTo(SELLER);
        assertThat(reloaded.getAmount()).isEqualByComparingTo("100.00000000");
        assertThat(reloaded.getCurrency()).isEqualTo("USDT");
        assertThat(reloaded.getState()).isEqualTo(EscrowOrder.State.OPEN.name());
        assertThat(reloaded.getCreatedAt()).isEqualTo(T0);
    }

    @Test
    @DisplayName("状态推进可持久化——写回后重读仍是新状态")
    void stateTransitionPersists() {
        EscrowOrder order = store.save(newOrder(BUYER, SELLER, T0));
        order.markLocked(T0.plus(Duration.ofHours(1)));
        store.save(order);

        flushAndClear();
        assertThat(repository.findById(order.getId()).orElseThrow().currentState())
                .isEqualTo(EscrowOrder.State.LOCKED);
    }

    // ── 历史汇总：T23 进行中笔数 ────────────────────────────────────────

    @Test
    @DisplayName("进行中笔数只计未终态订单（买卖双方任一方都算）")
    void activeCountCountsOnlyInFlight() {
        EscrowOrder inFlight = newOrder(BUYER, SELLER, T0);
        store.save(inFlight);

        assertThat(history.snapshotOf(BUYER).activeTradeCount()).isEqualTo(1);
        assertThat(history.snapshotOf(SELLER).activeTradeCount()).isEqualTo(1);
        assertThat(history.snapshotOf(9999L).activeTradeCount()).isZero();
    }

    @Test
    @DisplayName("终态订单不计入进行中笔数")
    void terminalOrdersDoNotCountAsActive() {
        EscrowOrder done = newOrder(BUYER, SELLER, T0);
        store.save(done);
        done.markLocked(T0);
        done.markReleased(T0.plus(Duration.ofHours(2)));
        store.save(done);

        assertThat(history.snapshotOf(BUYER).activeTradeCount()).isZero();
    }

    @Test
    @DisplayName("多笔进行中累加计数")
    void multipleActiveTradesAccumulate() {
        store.save(newOrder(BUYER, SELLER, T0));
        store.save(newOrder(BUYER, 3003L, T0));
        store.save(newOrder(4004L, BUYER, T0));

        assertThat(history.snapshotOf(BUYER).activeTradeCount()).isEqualTo(3);
    }

    // ── 历史汇总：T24 最近完成时刻 ──────────────────────────────────────

    @Test
    @DisplayName("最近完成时刻取终态订单的更新时刻最大值")
    void lastCompletedTakesLatestTerminal() {
        EscrowOrder early = newOrder(BUYER, SELLER, T0);
        store.save(early);
        early.markLocked(T0);
        early.markReleased(T0.plus(Duration.ofHours(1)));
        store.save(early);

        EscrowOrder later = newOrder(BUYER, 3003L, T0);
        store.save(later);
        later.markLocked(T0);
        later.markRefunded("协商", T0.plus(Duration.ofHours(5)));
        store.save(later);

        assertThat(history.snapshotOf(BUYER).lastCompletedTradeAt())
                .isEqualTo(T0.plus(Duration.ofHours(5)));
    }

    @Test
    @DisplayName("无终态订单 → lastCompletedTradeAt 为空")
    void noTerminalOrderMeansNoLastCompleted() {
        store.save(newOrder(BUYER, SELLER, T0));

        assertThat(history.snapshotOf(BUYER).lastCompletedTradeAt()).isNull();
    }

    @Test
    @DisplayName("进行中订单不影响最近完成时刻")
    void activeOrderDoesNotAffectLastCompleted() {
        EscrowOrder done = newOrder(BUYER, SELLER, T0);
        store.save(done);
        done.markLocked(T0);
        // LOCKED 之后只能走 RELEASED/REFUNDED 终态——CANCELLED 被守卫拒绝
        // （资金已动的订单不得就地取消），故此处用退款作为终态。
        done.markRefunded("协商退款", T0.plus(Duration.ofHours(1)));
        store.save(done);

        store.save(newOrder(BUYER, 3003L, T0.plus(Duration.ofHours(9))));

        assertThat(history.snapshotOf(BUYER).lastCompletedTradeAt())
                .isEqualTo(T0.plus(Duration.ofHours(1)));
    }

    // ── 历史汇总：T25 未决争议 ──────────────────────────────────────────

    @Test
    @DisplayName("DISPUTED 态订单 → hasUnresolvedDispute 为真（买卖双方都受影响）")
    void disputedOrderFlagsBothParties() {
        EscrowOrder disputed = newOrder(BUYER, SELLER, T0);
        store.save(disputed);
        disputed.markLocked(T0);
        disputed.markDisputed("货不对板", T0.plus(Duration.ofHours(1)));
        store.save(disputed);

        assertThat(history.snapshotOf(BUYER).hasUnresolvedDispute()).isTrue();
        assertThat(history.snapshotOf(SELLER).hasUnresolvedDispute()).isTrue();
        assertThat(history.snapshotOf(9999L).hasUnresolvedDispute()).isFalse();
    }

    @Test
    @DisplayName("争议经裁决转终态后，不再算未决争议")
    void resolvedDisputeClearsFlag() {
        EscrowOrder disputed = newOrder(BUYER, SELLER, T0);
        store.save(disputed);
        disputed.markLocked(T0);
        disputed.markDisputed("争议", T0.plus(Duration.ofHours(1)));
        store.save(disputed);
        disputed.markReleased(T0.plus(Duration.ofHours(2)));
        store.save(disputed);

        assertThat(history.snapshotOf(BUYER).hasUnresolvedDispute()).isFalse();
    }

    // ── 端口契约：fail-closed ───────────────────────────────────────────

    @Test
    @DisplayName("save 传 null → 抛异常（端口契约：不得返回 null，也不得静默接受）")
    void saveNullFailsClosed() {
        assertThatThrownBy(() -> store.save(null)).isInstanceOf(EscrowException.class);
    }

    @Test
    @DisplayName("snapshotOf 返回的 subjectId 与查询对象一致（供服务层核对归属）")
    void snapshotCarriesSubjectId() {
        store.save(newOrder(BUYER, SELLER, T0));

        assertThat(history.snapshotOf(BUYER).subjectId()).isEqualTo(BUYER);
    }
}
