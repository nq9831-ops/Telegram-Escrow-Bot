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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 交易币种白名单（Wave 0）的行为固定测试。
 *
 * <h2>为什么需要它</h2>
 * <p>此前币种在服务端<b>没有任何白名单</b>——{@code EscrowOrder.currency} 是自由字符串，
 * {@link TradeInitiationRequest} 只校验非空白。这意味着调用方可以创建任意币种的订单，
 * 而下游资金流程只结算 TON 与 TON 链上的 USDT。白名单必须<b>fail-closed</b>：
 * 不在表内一律拒绝，含大小写归一，避免 {@code "usdt"} 与 {@code "USDT"} 在库里并存。
 *
 * <p>本测试是<b>纯 POJO 测试</b>：币种判定不碰网络/数据库，无需 Spring 上下文。
 */
class TradeCurrencyTest {

    private static final long BUYER = 1001L;
    private static final long SELLER = 2002L;
    private static final BigDecimal AMOUNT = new BigDecimal("100.00");

    @Nested
    @DisplayName("白名单取值")
    class Supported {

        @Test
        @DisplayName("TON 与 USDT 通过，返回规范枚举")
        void tonAndUsdtSupported() {
            assertThat(TradeCurrency.requireSupported("TON")).isEqualTo(TradeCurrency.TON);
            assertThat(TradeCurrency.requireSupported("USDT")).isEqualTo(TradeCurrency.USDT);
        }

        @Test
        @DisplayName("大小写与首尾空白归一为规范形式")
        void normalizesCaseAndWhitespace() {
            assertThat(TradeCurrency.requireSupported("ton")).isEqualTo(TradeCurrency.TON);
            assertThat(TradeCurrency.requireSupported("usdt")).isEqualTo(TradeCurrency.USDT);
            assertThat(TradeCurrency.requireSupported("  UsDt  ")).isEqualTo(TradeCurrency.USDT);
        }
    }

    @Nested
    @DisplayName("fail-closed：其余币种一律拒绝")
    class Rejected {

        @Test
        @DisplayName("USDC / BTC / ETH / DOGE 等不在表内 → 拒，且提示「不支持」")
        void otherTickersRejected() {
            for (String unsupported : new String[]{"USDC", "BTC", "ETH", "DOGE", "TONX", "USDT-TRC20"}) {
                assertThatThrownBy(() -> TradeCurrency.requireSupported(unsupported))
                        .as("币种 %s 不应被接受", unsupported)
                        .isInstanceOf(EscrowException.class)
                        .hasMessageContaining("不支持");
            }
        }

        @Test
        @DisplayName("缺失或空白 → 拒")
        void blankOrNullRejected() {
            assertThatThrownBy(() -> TradeCurrency.requireSupported(null))
                    .isInstanceOf(EscrowException.class);
            assertThatThrownBy(() -> TradeCurrency.requireSupported(""))
                    .isInstanceOf(EscrowException.class);
            assertThatThrownBy(() -> TradeCurrency.requireSupported("   "))
                    .isInstanceOf(EscrowException.class);
        }
    }

    @Nested
    @DisplayName("接入 TradeInitiationRequest（一处收口：命令层与 /api/trade/create 同受约束）")
    class WiredIntoRequest {

        @Test
        @DisplayName("不支持的币种在装配请求时即被拦下")
        void requestRejectsUnsupportedCurrency() {
            assertThatThrownBy(() ->
                    new TradeInitiationRequest(BUYER, SELLER, AMOUNT, "BTC"))
                    .isInstanceOf(EscrowException.class)
                    .hasMessageContaining("不支持");
        }

        @Test
        @DisplayName("请求只保留规范形式（usdt → USDT）")
        void requestStoresCanonicalForm() {
            TradeInitiationRequest lower =
                    new TradeInitiationRequest(BUYER, SELLER, AMOUNT, "usdt");
            TradeInitiationRequest upper =
                    new TradeInitiationRequest(BUYER, SELLER, AMOUNT, "USDT");

            assertThat(lower.currency()).isEqualTo("USDT");
            assertThat(upper.currency()).isEqualTo("USDT");
        }
    }
}
