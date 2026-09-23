package com.tg.escrow.escrow;

import com.tg.escrow.common.EscrowException;
import com.tg.escrow.escrow.EscrowOrder.State;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 担保订单状态机的行为固定测试。
 *
 * <p>本测试是<b>纯 POJO 测试</b>：不启动 Spring 上下文、不连数据库——状态机是纯逻辑，
 * 用真库验证它只会让测试变慢而不增加可信度。
 */
class EscrowOrderTest {

    private static final long BUYER = 1001L;
    private static final long SELLER = 2002L;
    private static final BigDecimal AMOUNT = new BigDecimal("100.00000000");
    private static final String CURRENCY = "USDT";

    private static final Instant T0 = Instant.parse("2026-09-23T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-09-23T01:00:00Z");

    private EscrowOrder newOrder() {
        return new EscrowOrder(BUYER, SELLER, AMOUNT, CURRENCY, T0);
    }

    @Nested
    @DisplayName("初始状态")
    class InitialState {

        @Test
        @DisplayName("新建订单恒从 OPEN 起步，金额与币种原样保留")
        void startsAtOpen() {
            EscrowOrder order = newOrder();

            assertThat(order.currentState()).isEqualTo(State.OPEN);
            assertThat(order.getBuyerUserId()).isEqualTo(BUYER);
            assertThat(order.getSellerUserId()).isEqualTo(SELLER);
            assertThat(order.getAmount()).isEqualByComparingTo(AMOUNT);
            assertThat(order.getCurrency()).isEqualTo(CURRENCY);
            assertThat(order.getCreatedAt()).isEqualTo(T0);
            assertThat(order.getUpdatedAt()).isEqualTo(T0);
            assertThat(order.getId()).isNull();
        }

        @Test
        @DisplayName("金额用 BigDecimal 精确比较，不受二进制浮点尾差影响")
        void amountIsExactDecimal() {
            EscrowOrder a = new EscrowOrder(BUYER, SELLER, new BigDecimal("0.1"), CURRENCY, T0);
            EscrowOrder b = new EscrowOrder(BUYER, SELLER, new BigDecimal("0.10"), CURRENCY, T0);

            // 0.1 与 0.10 在 BigDecimal 下 compareTo 相等（scale 不同但值相同）
            assertThat(a.getAmount()).isEqualByComparingTo(b.getAmount());
        }
    }

    @Nested
    @DisplayName("合法迁移")
    class LegalTransitions {

        @Test
        @DisplayName("OPEN --markConfirmed--> CONFIRMED")
        void openToConfirmed() {
            EscrowOrder order = newOrder();
            order.markConfirmed(T1);

            assertThat(order.currentState()).isEqualTo(State.CONFIRMED);
            assertThat(order.getUpdatedAt()).isEqualTo(T1);
        }

        @Test
        @DisplayName("OPEN --markLocked--> LOCKED（骨架流程无卖方确认节点，允许直锁）")
        void openToLocked() {
            EscrowOrder order = newOrder();
            order.markLocked(T1);

            assertThat(order.currentState()).isEqualTo(State.LOCKED);
        }

        @Test
        @DisplayName("CONFIRMED --markLocked--> LOCKED")
        void confirmedToLocked() {
            EscrowOrder order = newOrder();
            order.markConfirmed(T1);
            order.markLocked(T1);

            assertThat(order.currentState()).isEqualTo(State.LOCKED);
        }

        @Test
        @DisplayName("LOCKED --markDelivered--> DELIVERED")
        void lockedToDelivered() {
            EscrowOrder order = locked();
            order.markDelivered(T1);

            assertThat(order.currentState()).isEqualTo(State.DELIVERED);
        }

        @Test
        @DisplayName("LOCKED --markDisputed--> DISPUTED，并记录争议理由")
        void lockedToDisputed() {
            EscrowOrder order = locked();
            order.markDisputed("未收到货", T1);

            assertThat(order.currentState()).isEqualTo(State.DISPUTED);
            assertThat(order.getReason()).isEqualTo("未收到货");
        }

        @Test
        @DisplayName("DELIVERED --markDisputed--> DISPUTED（交付后仍可争议）")
        void deliveredToDisputed() {
            EscrowOrder order = locked();
            order.markDelivered(T1);
            order.markDisputed("货不对板", T1);

            assertThat(order.currentState()).isEqualTo(State.DISPUTED);
        }

        @Test
        @DisplayName("LOCKED/DELIVERED/DISPUTED --markReleased--> RELEASED（放款给卖家）")
        void toReleased() {
            EscrowOrder fromLocked = locked();
            fromLocked.markReleased(T1);
            assertThat(fromLocked.currentState()).isEqualTo(State.RELEASED);

            EscrowOrder fromDelivered = locked();
            fromDelivered.markDelivered(T1);
            fromDelivered.markReleased(T1);
            assertThat(fromDelivered.currentState()).isEqualTo(State.RELEASED);

            EscrowOrder fromDisputed = locked();
            fromDisputed.markDisputed("争议", T1);
            fromDisputed.markReleased(T1);
            assertThat(fromDisputed.currentState()).isEqualTo(State.RELEASED);
        }

        @Test
        @DisplayName("LOCKED/DELIVERED/DISPUTED --markRefunded--> REFUNDED（退款给买家）")
        void toRefunded() {
            EscrowOrder fromLocked = locked();
            fromLocked.markRefunded("取消", T1);
            assertThat(fromLocked.currentState()).isEqualTo(State.REFUNDED);

            EscrowOrder fromDisputed = locked();
            fromDisputed.markDisputed("争议", T1);
            fromDisputed.markRefunded("裁决退款", T1);
            assertThat(fromDisputed.currentState()).isEqualTo(State.REFUNDED);
            assertThat(fromDisputed.getReason()).isEqualTo("裁决退款");
        }
    }

    @Nested
    @DisplayName("取消守卫：资金已托管的订单不得就地取消")
    class CancelGuard {

        @Test
        @DisplayName("OPEN --markCancelled--> CANCELLED")
        void openCanBeCancelled() {
            EscrowOrder order = newOrder();
            order.markCancelled("买家反悔", T1);

            assertThat(order.currentState()).isEqualTo(State.CANCELLED);
        }

        @Test
        @DisplayName("CONFIRMED --markCancelled--> CANCELLED")
        void confirmedCanBeCancelled() {
            EscrowOrder order = newOrder();
            order.markConfirmed(T1);
            order.markCancelled("协商取消", T1);

            assertThat(order.currentState()).isEqualTo(State.CANCELLED);
        }

        @Test
        @DisplayName("LOCKED 不可取消——必须走退款路径，不能用取消绕过资金流程")
        void lockedCannotBeCancelled() {
            EscrowOrder order = locked();

            assertThatThrownBy(() -> order.markCancelled("想省事", T1))
                    .isInstanceOf(EscrowException.class)
                    .hasMessageContaining("状态迁移非法");

            assertThat(order.currentState()).isEqualTo(State.LOCKED);
        }

        @Test
        @DisplayName("DELIVERED 不可取消")
        void deliveredCannotBeCancelled() {
            EscrowOrder order = locked();
            order.markDelivered(T1);

            assertThatThrownBy(() -> order.markCancelled("想省事", T1))
                    .isInstanceOf(EscrowException.class);

            assertThat(order.currentState()).isEqualTo(State.DELIVERED);
        }
    }

    @Nested
    @DisplayName("fail-closed：非法迁移一律拒绝")
    class FailClosed {

        @Test
        @DisplayName("OPEN 直接 markReleased 被拒——越级放款等于凭空给钱")
        void cannotReleaseFromOpen() {
            EscrowOrder order = newOrder();

            assertThatThrownBy(() -> order.markReleased(T1))
                    .isInstanceOf(EscrowException.class)
                    .hasMessageContaining("状态迁移非法");

            assertThat(order.currentState()).isEqualTo(State.OPEN);
        }

        @Test
        @DisplayName("OPEN 直接 markDelivered 被拒")
        void cannotDeliverFromOpen() {
            EscrowOrder order = newOrder();

            assertThatThrownBy(() -> order.markDelivered(T1))
                    .isInstanceOf(EscrowException.class);

            assertThat(order.currentState()).isEqualTo(State.OPEN);
        }

        @Test
        @DisplayName("终态 RELEASED 不可再迁移")
        void releasedIsTerminal() {
            EscrowOrder order = locked();
            order.markReleased(T1);

            assertThatThrownBy(() -> order.markRefunded("反悔", T1))
                    .isInstanceOf(EscrowException.class);
            assertThatThrownBy(() -> order.markDisputed("反悔", T1))
                    .isInstanceOf(EscrowException.class);

            assertThat(order.currentState()).isEqualTo(State.RELEASED);
        }

        @Test
        @DisplayName("终态 REFUNDED 不可再迁移")
        void refundedIsTerminal() {
            EscrowOrder order = locked();
            order.markRefunded("取消", T1);

            assertThatThrownBy(() -> order.markReleased(T1))
                    .isInstanceOf(EscrowException.class);

            assertThat(order.currentState()).isEqualTo(State.REFUNDED);
        }

        @Test
        @DisplayName("终态 CANCELLED 不可再迁移")
        void cancelledIsTerminal() {
            EscrowOrder order = newOrder();
            order.markCancelled("取消", T1);

            assertThatThrownBy(() -> order.markLocked(T1))
                    .isInstanceOf(EscrowException.class);

            assertThat(order.currentState()).isEqualTo(State.CANCELLED);
        }
    }

    private EscrowOrder locked() {
        EscrowOrder order = newOrder();
        order.markLocked(T1);
        return order;
    }
}
