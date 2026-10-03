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

import com.tg.escrow.common.EscrowException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 待接受邀请（{@link TradeInvite}）聚合的行为固定测试。
 *
 * <p>纯 POJO 测试：不启 Spring、不连库。守卫是纯逻辑，用真库验证只会变慢。
 */
class TradeInviteTest {

    private static final long BUYER = 1001L;
    private static final long ACCEPTOR = 2002L;
    private static final BigDecimal AMOUNT = new BigDecimal("100.00000000");
    private static final String CURRENCY = "TON";
    private static final String TOKEN = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6";
    private static final Instant T0 = Instant.parse("2026-09-24T00:00:00Z");
    private static final Instant EXPIRES = T0.plus(Duration.ofHours(24));

    private static TradeInvite invite() {
        return new TradeInvite(BUYER, AMOUNT, CURRENCY, TOKEN, T0, EXPIRES);
    }

    @Nested
    @DisplayName("构造期校验")
    class Construction {

        @Test
        @DisplayName("新建邀请未被消费，字段原样保留")
        void startsUnconsumed() {
            TradeInvite invite = invite();

            assertThat(invite.isConsumed()).isFalse();
            assertThat(invite.getAcceptedOrderId()).isNull();
            assertThat(invite.getBuyerUserId()).isEqualTo(BUYER);
            assertThat(invite.getAmount()).isEqualByComparingTo(AMOUNT);
            assertThat(invite.getCurrency()).isEqualTo(CURRENCY);
            assertThat(invite.getToken()).isEqualTo(TOKEN);
            assertThat(invite.getCreatedAt()).isEqualTo(T0);
            assertThat(invite.getExpiresAt()).isEqualTo(EXPIRES);
            assertThat(invite.getId()).isNull();
        }

        @Test
        @DisplayName("令牌缺失或空白 → 拒（无令牌则不可被接受）")
        void rejectsBlankToken() {
            assertThatThrownBy(() -> new TradeInvite(BUYER, AMOUNT, CURRENCY, null, T0, EXPIRES))
                    .isInstanceOf(EscrowException.class);
            assertThatThrownBy(() -> new TradeInvite(BUYER, AMOUNT, CURRENCY, "   ", T0, EXPIRES))
                    .isInstanceOf(EscrowException.class);
        }

        @Test
        @DisplayName("金额非正 → 拒（资金字段）")
        void rejectsNonPositiveAmount() {
            assertThatThrownBy(() -> new TradeInvite(BUYER, null, CURRENCY, TOKEN, T0, EXPIRES))
                    .isInstanceOf(EscrowException.class);
            assertThatThrownBy(() -> new TradeInvite(BUYER, BigDecimal.ZERO, CURRENCY, TOKEN, T0, EXPIRES))
                    .isInstanceOf(EscrowException.class);
            assertThatThrownBy(() -> new TradeInvite(BUYER, new BigDecimal("-1"), CURRENCY, TOKEN, T0, EXPIRES))
                    .isInstanceOf(EscrowException.class);
        }

        @Test
        @DisplayName("币种不在白名单 → 拒（复用 TradeCurrency）")
        void rejectsUnsupportedCurrency() {
            assertThatThrownBy(() -> new TradeInvite(BUYER, AMOUNT, "BTC", TOKEN, T0, EXPIRES))
                    .isInstanceOf(EscrowException.class)
                    .hasMessageContaining("不支持");
        }

        @Test
        @DisplayName("币种归一为规范形式（ton → TON）")
        void normalizesCurrency() {
            TradeInvite invite = new TradeInvite(BUYER, AMOUNT, "ton", TOKEN, T0, EXPIRES);

            assertThat(invite.getCurrency()).isEqualTo("TON");
        }

        @Test
        @DisplayName("有效期不晚于创建时刻 → 拒（否则一出生就过期）")
        void rejectsNonPositiveLifetime() {
            assertThatThrownBy(() -> new TradeInvite(BUYER, AMOUNT, CURRENCY, TOKEN, T0, T0))
                    .isInstanceOf(EscrowException.class);
            assertThatThrownBy(() -> new TradeInvite(BUYER, AMOUNT, CURRENCY, TOKEN, T0, T0.minusSeconds(1)))
                    .isInstanceOf(EscrowException.class);
        }
    }

    @Nested
    @DisplayName("accept 守卫（不可接受时 fail-closed）")
    class AcceptGuards {

        @Test
        @DisplayName("有效期内、未被消费、非本人 → 可接受")
        void acceptableWhenFresh() {
            assertThatCode(() -> invite().requireAcceptable(ACCEPTOR, T0.plus(Duration.ofHours(1))))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("恰好到期时刻仍可接受，严格晚于才过期（边界）")
        void expiryBoundaryIsInclusive() {
            assertThatCode(() -> invite().requireAcceptable(ACCEPTOR, EXPIRES))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("已过期 → 拒（EXPIRED）")
        void rejectsExpired() {
            assertThatThrownBy(() -> invite().requireAcceptable(ACCEPTOR, EXPIRES.plusSeconds(1)))
                    .isInstanceOf(TradeInviteException.class)
                    .extracting(e -> ((TradeInviteException) e).reason())
                    .isEqualTo(TradeInviteException.Reason.EXPIRED);
        }

        @Test
        @DisplayName("已被消费 → 拒（ALREADY_ACCEPTED）")
        void rejectsWhenConsumed() {
            TradeInvite invite = invite();
            invite.markAccepted(777L);

            assertThatThrownBy(() -> invite.requireAcceptable(ACCEPTOR, T0.plus(Duration.ofHours(1))))
                    .isInstanceOf(TradeInviteException.class)
                    .extracting(e -> ((TradeInviteException) e).reason())
                    .isEqualTo(TradeInviteException.Reason.ALREADY_ACCEPTED);
        }

        @Test
        @DisplayName("发起人接受自己的邀请 → 拒（SELF_ACCEPT，自交易让担保失去意义）")
        void rejectsSelfAccept() {
            assertThatThrownBy(() -> invite().requireAcceptable(BUYER, T0.plus(Duration.ofHours(1))))
                    .isInstanceOf(TradeInviteException.class)
                    .extracting(e -> ((TradeInviteException) e).reason())
                    .isEqualTo(TradeInviteException.Reason.SELF_ACCEPT);
        }
    }

    @Nested
    @DisplayName("消费")
    class Consumption {

        @Test
        @DisplayName("markAccepted 记录订单号并置为已消费")
        void recordsOrderId() {
            TradeInvite invite = invite();
            invite.markAccepted(4242L);

            assertThat(invite.isConsumed()).isTrue();
            assertThat(invite.getAcceptedOrderId()).isEqualTo(4242L);
        }

        @Test
        @DisplayName("不可二次消费——已接受的邀请再 mark 抛异常")
        void cannotAcceptTwice() {
            TradeInvite invite = invite();
            invite.markAccepted(1L);

            assertThatThrownBy(() -> invite.markAccepted(2L))
                    .isInstanceOf(EscrowException.class);
        }
    }
}
