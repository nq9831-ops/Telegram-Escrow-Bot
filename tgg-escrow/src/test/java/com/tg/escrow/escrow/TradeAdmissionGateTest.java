package com.tg.escrow.escrow;

import com.tg.escrow.common.EscrowException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 交易准入门禁的行为固定测试（原文档第六部分核心规则 T23/T24/T25）。
 *
 * <p>本测试把文档里三行规则钉成可执行断言：单笔限制、冷却期、争议冻结。
 * 重点覆盖<b>边界</b>（恰好满 24 小时算不算冷却内）与<b>数据异常</b>（负计数、未来时刻）
 * ——这两类恰好是「看起来没超限就放行」最容易漏掉的地方。
 */
class TradeAdmissionGateTest {

    /** 统一时刻：测试不依赖真实时钟。 */
    private static final Instant NOW = Instant.parse("2026-09-23T10:00:00Z");

    private static final long SUBJECT = 1001L;

    private static final TradeAdmissionPolicy DEFAULT_POLICY =
            new TradeAdmissionPolicy(1, Duration.ofHours(24));

    private static final TradeAdmissionGate GATE = new TradeAdmissionGate(DEFAULT_POLICY);

    private static TradeAdmissionContext context(int activeTradeCount,
                                                Instant lastCompletedTradeAt,
                                                boolean hasUnresolvedDispute) {
        return new TradeAdmissionContext(SUBJECT, activeTradeCount,
                lastCompletedTradeAt, hasUnresolvedDispute);
    }

    private static TradeAdmissionContext cleanSlate() {
        return context(0, null, false);
    }

    // ── 放行 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("无任何历史 → 放行，且 retryAfter 为空")
    void cleanSlateIsAdmitted() {
        TradeAdmissionDecision decision = GATE.check(cleanSlate(), NOW);

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.reason()).isEqualTo(TradeAdmissionDecision.Reason.ALLOWED);
        assertThat(decision.retryAfter()).isNull();
    }

    @Test
    @DisplayName("三条规则都不触发时才放行——不是「有一条通过即可」")
    void admitsOnlyWhenAllRulesPass() {
        // 进行中 0、冷却已过、无争议
        assertThat(GATE.check(context(0, NOW.minus(Duration.ofHours(25)), false), NOW).allowed())
                .isTrue();
    }

    // ── T23 单笔限制 ────────────────────────────────────────────────────

    @Test
    @DisplayName("T23：已有 1 笔进行中 → 拒绝（CONCURRENT_LIMIT）")
    void concurrentLimitRejects() {
        TradeAdmissionDecision decision = GATE.check(context(1, null, false), NOW);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo(TradeAdmissionDecision.Reason.CONCURRENT_LIMIT);
        assertThat(decision.retryAfter())
                .as("并发占用的解封时刻取决于对方履约，无法预估")
                .isNull();
    }

    @Test
    @DisplayName("T23 边界：数量低于上限放行，达到上限拒绝")
    void concurrentLimitBoundary() {
        TradeAdmissionGate twoSlots =
                new TradeAdmissionGate(new TradeAdmissionPolicy(2, Duration.ofHours(24)));

        assertThat(twoSlots.check(context(1, null, false), NOW).allowed())
                .as("1 < 2 应放行")
                .isTrue();
        assertThat(twoSlots.check(context(2, null, false), NOW).allowed())
                .as("达到上限 2 应拒绝")
                .isFalse();
    }

    // ── T24 单笔冷却期 ──────────────────────────────────────────────────

    @Test
    @DisplayName("T24：交易完成后 12 小时 → 拒绝（COOLDOWN），并给出可重试时刻")
    void cooldownRejectsAndGivesRetryAfter() {
        Instant lastCompleted = NOW.minus(Duration.ofHours(12));
        TradeAdmissionDecision decision =
                GATE.check(context(0, lastCompleted, false), NOW);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo(TradeAdmissionDecision.Reason.COOLDOWN);
        assertThat(decision.retryAfter()).isEqualTo(lastCompleted.plus(Duration.ofHours(24)));
    }

    @Test
    @DisplayName("T24：冷却期已满（25 小时前）→ 放行")
    void cooldownElapsedAdmits() {
        assertThat(GATE.check(context(0, NOW.minus(Duration.ofHours(25)), false), NOW).allowed())
                .isTrue();
    }

    @Test
    @DisplayName("T24 边界：恰好满 24 小时 → 放行（「24 小时内」不含整点）")
    void cooldownExactlyElapsedAdmits() {
        assertThat(GATE.check(context(0, NOW.minus(Duration.ofHours(24)), false), NOW).allowed())
                .isTrue();
    }

    @Test
    @DisplayName("T24：冷却期配置为 0 → 该条规则不生效（但 T23/T25 仍生效）")
    void zeroCooldownDisablesCooldownRuleOnly() {
        TradeAdmissionGate noCooldown =
                new TradeAdmissionGate(new TradeAdmissionPolicy(1, Duration.ZERO));

        assertThat(noCooldown.check(context(0, NOW.minus(Duration.ofMinutes(1)), false), NOW).allowed())
                .as("零冷却期下刚完成也能发起")
                .isTrue();
        assertThat(noCooldown.check(context(1, null, false), NOW).allowed())
                .as("T23 不受影响")
                .isFalse();
        assertThat(noCooldown.check(context(0, null, true), NOW).allowed())
                .as("T25 不受影响")
                .isFalse();
    }

    // ── T25 争议期间冻结 ────────────────────────────────────────────────

    @Test
    @DisplayName("T25：存在未决争议 → 拒绝（DISPUTE_HOLD），retryAfter 为空（无期限）")
    void disputeHoldRejectsWithoutRetryAfter() {
        TradeAdmissionDecision decision = GATE.check(context(0, null, true), NOW);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo(TradeAdmissionDecision.Reason.DISPUTE_HOLD);
        assertThat(decision.retryAfter())
                .as("争议何时了结不可预估")
                .isNull();
    }

    // ── 多条同时触发的优先级 ────────────────────────────────────────────

    @Test
    @DisplayName("优先级固定为 DISPUTE > CONCURRENT > COOLDOWN——争议无期限，最先告知")
    void precedenceIsFixed() {
        // 三条全中
        assertThat(GATE.check(context(5, NOW.minus(Duration.ofHours(1)), true), NOW).reason())
                .isEqualTo(TradeAdmissionDecision.Reason.DISPUTE_HOLD);

        // 争议 + 并发
        assertThat(GATE.check(context(5, null, true), NOW).reason())
                .isEqualTo(TradeAdmissionDecision.Reason.DISPUTE_HOLD);

        // 并发 + 冷却
        assertThat(GATE.check(context(5, NOW.minus(Duration.ofHours(1)), false), NOW).reason())
                .isEqualTo(TradeAdmissionDecision.Reason.CONCURRENT_LIMIT);
    }

    // ── fail-closed ─────────────────────────────────────────────────────

    @Test
    @DisplayName("fail-closed：上下文或时刻缺失 → 抛异常，绝不放行")
    void missingInputFailsClosed() {
        assertThatThrownBy(() -> GATE.check(null, NOW)).isInstanceOf(EscrowException.class);
        assertThatThrownBy(() -> GATE.check(cleanSlate(), null)).isInstanceOf(EscrowException.class);
    }

    @Test
    @DisplayName("fail-closed：进行中笔数为负（数据异常）→ 构造即抛，不据此放行")
    void negativeActiveCountFailsClosed() {
        assertThatThrownBy(() -> context(-1, null, false))
                .isInstanceOf(EscrowException.class)
                .hasMessageContaining("进行中笔数为负");
    }

    @Test
    @DisplayName("保守原则：完成时刻在未来（时钟偏移）→ 冷却期视为仍在，拒绝")
    void futureCompletionTimeIsConservative() {
        Instant future = NOW.plus(Duration.ofHours(3));
        TradeAdmissionDecision decision = GATE.check(context(0, future, false), NOW);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo(TradeAdmissionDecision.Reason.COOLDOWN);
    }

    // ── 策略构造校验 ────────────────────────────────────────────────────

    @Test
    @DisplayName("策略非法：并发上限 < 1 → 构造即抛（0 会让门禁永久拒绝一切）")
    void invalidConcurrentLimitFailsFast() {
        assertThatThrownBy(() -> new TradeAdmissionPolicy(0, Duration.ofHours(24)))
                .isInstanceOf(EscrowException.class);
        assertThatThrownBy(() -> new TradeAdmissionPolicy(-1, Duration.ofHours(24)))
                .isInstanceOf(EscrowException.class);
    }

    @Test
    @DisplayName("策略非法：冷却期为 null 或负数 → 构造即抛")
    void invalidCooldownFailsFast() {
        assertThatThrownBy(() -> new TradeAdmissionPolicy(1, null))
                .isInstanceOf(EscrowException.class);
        assertThatThrownBy(() -> new TradeAdmissionPolicy(1, Duration.ofHours(-1)))
                .isInstanceOf(EscrowException.class);
    }

    @Test
    @DisplayName("门禁未提供策略 → 构造即抛")
    void missingPolicyFailsFast() {
        assertThatThrownBy(() -> new TradeAdmissionGate(null)).isInstanceOf(EscrowException.class);
    }

    // ── 裁决自洽性 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("裁决自洽：放行必为 ALLOWED 且无 retryAfter，拒绝必带非 ALLOWED 原因")
    void decisionIsSelfConsistent() {
        assertThatThrownBy(() -> new TradeAdmissionDecision(true, TradeAdmissionDecision.Reason.COOLDOWN, null))
                .isInstanceOf(EscrowException.class);
        assertThatThrownBy(() -> new TradeAdmissionDecision(false, TradeAdmissionDecision.Reason.ALLOWED, null))
                .isInstanceOf(EscrowException.class);
        assertThatThrownBy(() -> new TradeAdmissionDecision(true, TradeAdmissionDecision.Reason.ALLOWED, NOW))
                .isInstanceOf(EscrowException.class);
    }
}
