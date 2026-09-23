package com.tg.escrow.chain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 链上余额交叉验证的行为固定测试。
 *
 * <p>这一层存在的理由：**资金判定不能采信单一来源**。第三方 RPC 可能返回过期数据、
 * 被限流降级、或（理论上）作恶。所以读取接口的返回值必须<b>携带证据</b>——
 * 哪些源确认过、最小确认数是多少——而不是一个裸的布尔。
 *
 * <p>三条纪律在本测试里被钉住：
 * <ol>
 *   <li>少于两个可用源 → 拒绝（无法交叉）；</li>
 *   <li>源之间不一致 → 拒绝，且<b>不选多数、不取最大</b>——分歧意味着至少一个源有问题，
 *       这时"多数"未必是真相；</li>
 *   <li>确认数取所有源的<b>最小值</b>（最悲观者）。</li>
 * </ol>
 */
class BalanceCrossVerifierTest {

    private static final String ADDRESS = "EQAbc...escrow";
    private static final int REQUIRED_CONFIRMATIONS = 12;

    /** 固定观测时刻，保证证据可比。 */
    private static final Instant T0 = Instant.parse("2026-09-23T10:00:00Z");

    private static ChainSource source(String name, String balance, int confirmations) {
        return new ChainSource() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public BalanceObservation observeBalance(String address) {
                return new BalanceObservation(name, new BigDecimal(balance), confirmations, T0);
            }
        };
    }

    private static ChainSource failingSource(String name) {
        return new ChainSource() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public BalanceObservation observeBalance(String address) {
                throw new ChainUnavailableException(name + " 不可用");
            }
        };
    }

    @Test
    @DisplayName("两个源一致且确认充足 → 返回带证据的结论（含来源集合与最小确认数）")
    void twoAgreeingSourcesWithEnoughConfirmations() {
        VerifiedBalance verified = BalanceCrossVerifier.read(ADDRESS,
                List.of(source("liteserver-a", "100.0", 20),
                        source("rpc-b", "100.0", 15)),
                REQUIRED_CONFIRMATIONS);

        assertThat(verified.balance()).isEqualByComparingTo("100.0");
        assertThat(verified.sources()).containsExactlyInAnyOrder("liteserver-a", "rpc-b");
        assertThat(verified.minConfirmations()).isEqualTo(15); // 取最小
    }

    @Test
    @DisplayName("只有一个可用源 → 拒绝：没有第二个源就无法交叉验证")
    void singleSourceIsRejected() {
        assertThatThrownBy(() -> BalanceCrossVerifier.read(ADDRESS,
                List.of(source("only-one", "100.0", 20)),
                REQUIRED_CONFIRMATIONS))
                .isInstanceOf(ChainUnavailableException.class)
                .hasMessageContaining("交叉验证");
    }

    @Test
    @DisplayName("两个源余额不一致 → 拒绝，且不选多数、不取最大")
    void disagreeingSourcesAreRejected() {
        assertThatThrownBy(() -> BalanceCrossVerifier.read(ADDRESS,
                List.of(source("liteserver-a", "100.0", 20),
                        source("rpc-b", "999.0", 20)),
                REQUIRED_CONFIRMATIONS))
                .isInstanceOf(ChainDisagreementException.class);
    }

    @Test
    @DisplayName("三个源里两个说 100、一个说 999 → 仍然拒绝（分歧时多数未必是真相）")
    void majorityDoesNotWinWhenSourcesDisagree() {
        assertThatThrownBy(() -> BalanceCrossVerifier.read(ADDRESS,
                List.of(source("a", "100.0", 20),
                        source("b", "100.0", 20),
                        source("c", "999.0", 20)),
                REQUIRED_CONFIRMATIONS))
                .isInstanceOf(ChainDisagreementException.class);
    }

    @Test
    @DisplayName("余额 scale 不同但数值相等 → 视为一致（1.0 与 1.00 是同一个余额）")
    void differentScaleButSameValueCountsAsAgreement() {
        VerifiedBalance verified = BalanceCrossVerifier.read(ADDRESS,
                List.of(source("a", "1.0", 20),
                        source("b", "1.00", 20)),
                REQUIRED_CONFIRMATIONS);

        assertThat(verified.balance()).isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("源之间一致但确认数不足 → 拒绝（链上最终性未达，属第三种语义：等待重试）")
    void insufficientConfirmationsIsRejected() {
        assertThatThrownBy(() -> BalanceCrossVerifier.read(ADDRESS,
                List.of(source("a", "100.0", 11),
                        source("b", "100.0", 50)),
                REQUIRED_CONFIRMATIONS))
                .isInstanceOf(ChainNotFinalizedException.class)
                .hasMessageContaining("确认");

        // 确认数取最小：一个源说 11、另一个说 50，仍不达标
        assertThatThrownBy(() -> BalanceCrossVerifier.read(ADDRESS,
                List.of(source("a", "100.0", 11),
                        source("b", "100.0", 50)),
                REQUIRED_CONFIRMATIONS))
                .isInstanceOf(ChainNotFinalizedException.class);
    }

    @Test
    @DisplayName("三个源里有一个故障 → 剩下两个一致则可继续（容忍单源故障，不容忍单源独断）")
    void oneFailingSourceToleratedWhenTwoAgree() {
        VerifiedBalance verified = BalanceCrossVerifier.read(ADDRESS,
                List.of(source("a", "100.0", 20),
                        failingSource("b"),
                        source("c", "100.0", 18)),
                REQUIRED_CONFIRMATIONS);

        assertThat(verified.sources()).containsExactlyInAnyOrder("a", "c");
        assertThat(verified.minConfirmations()).isEqualTo(18);
    }

    @Test
    @DisplayName("全部源故障 → 拒绝")
    void allSourcesFailingIsRejected() {
        assertThatThrownBy(() -> BalanceCrossVerifier.read(ADDRESS,
                List.of(failingSource("a"), failingSource("b")),
                REQUIRED_CONFIRMATIONS))
                .isInstanceOf(ChainUnavailableException.class);
    }

    @Test
    @DisplayName("源列表为空或 null → 拒绝")
    void emptySourceListIsRejected() {
        assertThatThrownBy(() -> BalanceCrossVerifier.read(ADDRESS, List.of(), REQUIRED_CONFIRMATIONS))
                .isInstanceOf(ChainUnavailableException.class);
        assertThatThrownBy(() -> BalanceCrossVerifier.read(ADDRESS, null, REQUIRED_CONFIRMATIONS))
                .isInstanceOf(ChainUnavailableException.class);
    }

    @Test
    @DisplayName("源抛非契约异常（实现 bug）→ 向上暴露，不静默当作『不可用』")
    void unexpectedSourceFailurePropagates() {
        ChainSource buggy = new ChainSource() {
            @Override
            public String name() {
                return "buggy";
            }

            @Override
            public BalanceObservation observeBalance(String address) {
                throw new IllegalStateException("实现 bug");
            }
        };

        assertThatThrownBy(() -> BalanceCrossVerifier.read(ADDRESS,
                List.of(buggy, source("b", "100.0", 20)),
                REQUIRED_CONFIRMATIONS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("实现 bug");
    }

    @Test
    @DisplayName("地址为空 → 拒绝（不拿空地址去查链）")
    void blankAddressIsRejected() {
        assertThatThrownBy(() -> BalanceCrossVerifier.read("  ",
                List.of(source("a", "1", 20), source("b", "1", 20)),
                REQUIRED_CONFIRMATIONS))
                .isInstanceOf(ChainUnavailableException.class);
    }

    @Test
    @DisplayName("确认数门槛为负数 → 拒绝（说明调用方算错了，不该静默放行）")
    void negativeRequiredConfirmationsIsRejected() {
        assertThatThrownBy(() -> BalanceCrossVerifier.read(ADDRESS,
                List.of(source("a", "1", 20), source("b", "1", 20)),
                -1))
                .isInstanceOf(ChainUnavailableException.class);
    }
}
