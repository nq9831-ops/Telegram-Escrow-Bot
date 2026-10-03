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

import com.tg.escrow.escrow.FeeLedgerRepository;
import com.tg.escrow.escrow.FeeLedgerRow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 费用台账查询端点（ET-29/30 对账面）的行为固定测试。
 *
 * <p>金额以<b>精确字符串</b>出参（{@code toPlainString}，不去尾零）——对账按字核对，
 * 与面向用户的 MoneyFormat 口径刻意不同（机器出口保留精确精度，同裁决 API 惯例）。
 */
class AdminFeeLedgerControllerTest {

    @Test
    @DisplayName("列表：金额为精确字符串（100.00000000 保留八位尾零——对账按字核对）")
    void listsExactAmounts() {
        FeeLedgerRepository repository = mock(FeeLedgerRepository.class);
        FeeLedgerRow row = new FeeLedgerRow(7L, "USDT", new BigDecimal("100.00000000"),
                new BigDecimal("1.000000"), new BigDecimal("99.00000000"), true);
        when(repository.findTop100ByOrderByIdDesc()).thenReturn(List.of(row));

        List<AdminFeeLedgerController.FeeLedgerItem> items =
                new AdminFeeLedgerController(repository).list();

        assertThat(items).singleElement().satisfies(item -> {
            assertThat(item.orderId()).isEqualTo(7L);
            assertThat(item.currency()).isEqualTo("USDT");
            assertThat(item.grossAmount()).isEqualTo("100.00000000");
            assertThat(item.platformFee()).isEqualTo("1.000000");
            assertThat(item.sellerNet()).isEqualTo("99.00000000");
            assertThat(item.firstOrderWaived()).isTrue();
        });
    }

    @Test
    @DisplayName("构造：仓库不可为空")
    void repositoryRequired() {
        assertThatThrownBy(() -> new AdminFeeLedgerController(null))
                .isInstanceOf(com.tg.escrow.common.TggException.class);
    }
}
