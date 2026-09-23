package com.tg.escrow.escrow;

import com.tg.escrow.common.EscrowException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 交易创建路径的行为固定测试。
 *
 * <p>本类补上 {@link TradeAdmissionGate} 与真实业务之间缺的那一环：<b>谁来调用它</b>。
 * 此前 Gate 是零调用方的孤岛，T23/T24/T25 三条规则即使判对了也无人执行；
 * 本服务把「发起一笔新交易」这个动作收口成一次判定 + 一次落单。
 *
 * <p>其中两条用例正是上一轮标记为 blocked 的用户级验收：
 * 并发超限被拒、冷却期内被拒并给出可重试时刻。
 */
class EscrowTradeServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-23T10:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static final long BUYER = 1001L;
    private static final long SELLER = 2002L;
    private static final BigDecimal AMOUNT = new BigDecimal("100.00");

    private static final TradeAdmissionPolicy POLICY =
            new TradeAdmissionPolicy(1, Duration.ofHours(24));

    private static TradeAdmissionContext history(int active, Instant lastCompleted, boolean disputed) {
        return new TradeAdmissionContext(BUYER, active, lastCompleted, disputed);
    }

    private static TradeAdmissionContext cleanHistory() {
        return history(0, null, false);
    }

    private static TradeInitiationRequest request() {
        return new TradeInitiationRequest(BUYER, SELLER, AMOUNT, "USDT");
    }

    /** 记录 save 调用次数的内存存储，用于断言「拒绝时绝不落单」。 */
    private static final class RecordingStore implements EscrowOrderStore {
        final AtomicInteger saves = new AtomicInteger();
        EscrowOrder last;

        @Override
        public EscrowOrder save(EscrowOrder order) {
            saves.incrementAndGet();
            this.last = order;
            return order;
        }
    }

    private static EscrowTradeService service(TradeAdmissionContext history, RecordingStore store) {
        return new EscrowTradeService(
                new TradeAdmissionGate(POLICY),
                subject -> history,
                store,
                FIXED_CLOCK);
    }

    private static EscrowTradeService service(TradeAdmissionContext history) {
        return service(history, new RecordingStore());
    }

    // ── 放行并落单 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("无任何历史 → 创建成功，订单落在 OPEN 态且字段原样保留")
    void cleanBuyerCreatesOpenOrder() {
        RecordingStore store = new RecordingStore();
        TradeInitiationResult result = service(cleanHistory(), store).initiate(request());

        assertThat(result.status()).isEqualTo(TradeInitiationResult.Status.CREATED);
        assertThat(result.order()).isNotNull();
        assertThat(result.order().currentState()).isEqualTo(EscrowOrder.State.OPEN);
        assertThat(result.order().getBuyerUserId()).isEqualTo(BUYER);
        assertThat(result.order().getSellerUserId()).isEqualTo(SELLER);
        assertThat(result.order().getAmount()).isEqualByComparingTo(AMOUNT);
        assertThat(result.order().getCurrency()).isEqualTo("USDT");
        assertThat(result.order().getCreatedAt()).isEqualTo(NOW);
        assertThat(store.saves.get()).isEqualTo(1);
    }

    // ── blocked 验收一：T23 并发超限 ────────────────────────────────────

    @Test
    @DisplayName("【验收】买方已有 1 笔进行中时发起新交易 → 被拒并回 CONCURRENT_LIMIT，且绝不落单")
    void concurrentLimitRejectsNewTrade() {
        RecordingStore store = new RecordingStore();
        TradeInitiationResult result =
                service(history(1, null, false), store).initiate(request());

        assertThat(result.status()).isEqualTo(TradeInitiationResult.Status.REJECTED);
        assertThat(result.decision().reason())
                .isEqualTo(TradeAdmissionDecision.Reason.CONCURRENT_LIMIT);
        assertThat(result.order()).isNull();
        assertThat(store.saves.get())
                .as("被拒时不得落单——否则限流形同虚设")
                .isZero();
    }

    // ── blocked 验收二：T24 冷却期 ──────────────────────────────────────

    @Test
    @DisplayName("【验收】交易完成后 24 小时内再发起 → 被拒（COOLDOWN）并给出可重试时刻")
    void cooldownRejectsAndGivesRetryAfter() {
        Instant lastCompleted = NOW.minus(Duration.ofHours(12));
        RecordingStore store = new RecordingStore();
        TradeInitiationResult result =
                service(history(0, lastCompleted, false), store).initiate(request());

        assertThat(result.status()).isEqualTo(TradeInitiationResult.Status.REJECTED);
        assertThat(result.decision().reason()).isEqualTo(TradeAdmissionDecision.Reason.COOLDOWN);
        assertThat(result.decision().retryAfter())
                .isEqualTo(lastCompleted.plus(Duration.ofHours(24)));
        assertThat(store.saves.get()).isZero();
    }

    // ── T25 争议冻结 ────────────────────────────────────────────────────

    @Test
    @DisplayName("【验收】存在未决争议 → 被拒（DISPUTE_HOLD）且绝不落单")
    void disputeHoldRejectsNewTrade() {
        RecordingStore store = new RecordingStore();
        TradeInitiationResult result =
                service(history(0, null, true), store).initiate(request());

        assertThat(result.status()).isEqualTo(TradeInitiationResult.Status.REJECTED);
        assertThat(result.decision().reason()).isEqualTo(TradeAdmissionDecision.Reason.DISPUTE_HOLD);
        assertThat(store.saves.get()).isZero();
    }

    @Test
    @DisplayName("冷却期已满（25 小时前）→ 创建成功")
    void cooldownElapsedAllows() {
        TradeInitiationResult result =
                service(history(0, NOW.minus(Duration.ofHours(25)), false)).initiate(request());

        assertThat(result.status()).isEqualTo(TradeInitiationResult.Status.CREATED);
    }

    // ── 请求校验（fail-closed）─────────────────────────────────────────

    @Test
    @DisplayName("请求为 null → 抛异常，绝不落单")
    void nullRequestFailsClosed() {
        RecordingStore store = new RecordingStore();
        assertThatThrownBy(() -> service(cleanHistory(), store).initiate(null))
                .isInstanceOf(EscrowException.class);
        assertThat(store.saves.get()).isZero();
    }

    @Test
    @DisplayName("买方与卖方同一人 → 抛异常（自交易会让担保失去意义）")
    void selfTradeIsRejected() {
        assertThatThrownBy(() -> service(cleanHistory()).initiate(
                new TradeInitiationRequest(BUYER, BUYER, AMOUNT, "USDT")))
                .isInstanceOf(EscrowException.class)
                .hasMessageContaining("同一人");
    }

    @Test
    @DisplayName("金额为 null / 零 / 负 → 抛异常（资金字段不容含糊）")
    void invalidAmountFailsClosed() {
        assertThatThrownBy(() -> service(cleanHistory()).initiate(
                new TradeInitiationRequest(BUYER, SELLER, null, "USDT")))
                .isInstanceOf(EscrowException.class);
        assertThatThrownBy(() -> service(cleanHistory()).initiate(
                new TradeInitiationRequest(BUYER, SELLER, BigDecimal.ZERO, "USDT")))
                .isInstanceOf(EscrowException.class);
        assertThatThrownBy(() -> service(cleanHistory()).initiate(
                new TradeInitiationRequest(BUYER, SELLER, new BigDecimal("-1"), "USDT")))
                .isInstanceOf(EscrowException.class);
    }

    @Test
    @DisplayName("币种缺失或空白 → 抛异常")
    void blankCurrencyFailsClosed() {
        assertThatThrownBy(() -> service(cleanHistory()).initiate(
                new TradeInitiationRequest(BUYER, SELLER, AMOUNT, null)))
                .isInstanceOf(EscrowException.class);
        assertThatThrownBy(() -> service(cleanHistory()).initiate(
                new TradeInitiationRequest(BUYER, SELLER, AMOUNT, "   ")))
                .isInstanceOf(EscrowException.class);
    }

    // ── 下游故障不得伪装成放行 ──────────────────────────────────────────

    @Test
    @DisplayName("历史查询抛异常 → 原样上抛，绝不落单（查不清历史就不该放行）")
    void historyFailurePropagatesAndDoesNotSave() {
        RecordingStore store = new RecordingStore();
        EscrowTradeService broken = new EscrowTradeService(
                new TradeAdmissionGate(POLICY),
                subject -> { throw new IllegalStateException("历史查询挂了"); },
                store,
                FIXED_CLOCK);

        assertThatThrownBy(() -> broken.initiate(request()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(store.saves.get()).isZero();
    }

    @Test
    @DisplayName("落单抛异常 → 原样上抛，不伪装成创建成功")
    void storeFailurePropagates() {
        EscrowTradeService broken = new EscrowTradeService(
                new TradeAdmissionGate(POLICY),
                subject -> cleanHistory(),
                order -> { throw new IllegalStateException("落单失败"); },
                FIXED_CLOCK);

        assertThatThrownBy(() -> broken.initiate(request()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("历史归属错位（返回了别人的记录）→ 抛异常，绝不落单（按他人情况放行等于门禁失效）")
    void mismatchedHistorySubjectFailsClosed() {
        RecordingStore store = new RecordingStore();
        // 端口回了一个 subjectId 不是买方的记录
        EscrowTradeService broken = new EscrowTradeService(
                new TradeAdmissionGate(POLICY),
                subject -> new TradeAdmissionContext(subject + 999, 0, null, false),
                store,
                FIXED_CLOCK);

        assertThatThrownBy(() -> broken.initiate(request()))
                .isInstanceOf(EscrowException.class)
                .hasMessageContaining("历史归属错位");
        assertThat(store.saves.get()).isZero();
    }

    @Test
    @DisplayName("未提供依赖（门禁/历史/存储/时钟）→ 构造即抛")
    void missingDependenciesFailFast() {
        assertThatThrownBy(() -> new EscrowTradeService(null, s -> cleanHistory(), new RecordingStore(), FIXED_CLOCK))
                .isInstanceOf(EscrowException.class);
        assertThatThrownBy(() -> new EscrowTradeService(new TradeAdmissionGate(POLICY), null, new RecordingStore(), FIXED_CLOCK))
                .isInstanceOf(EscrowException.class);
        assertThatThrownBy(() -> new EscrowTradeService(new TradeAdmissionGate(POLICY), s -> cleanHistory(), null, FIXED_CLOCK))
                .isInstanceOf(EscrowException.class);
        assertThatThrownBy(() -> new EscrowTradeService(new TradeAdmissionGate(POLICY), s -> cleanHistory(), new RecordingStore(), null))
                .isInstanceOf(EscrowException.class);
    }

    // ── 裁决自洽性 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("结果自洽：CREATED 必带订单，REJECTED 必无订单")
    void resultIsSelfConsistent() {
        EscrowOrder order = new EscrowOrder(BUYER, SELLER, AMOUNT, "USDT", NOW);

        assertThatThrownBy(() -> new TradeInitiationResult(
                TradeInitiationResult.Status.CREATED, null, TradeAdmissionDecision.allow()))
                .isInstanceOf(EscrowException.class);

        assertThatThrownBy(() -> new TradeInitiationResult(
                TradeInitiationResult.Status.REJECTED, order,
                TradeAdmissionDecision.reject(TradeAdmissionDecision.Reason.COOLDOWN, NOW)))
                .isInstanceOf(EscrowException.class);
    }
}
