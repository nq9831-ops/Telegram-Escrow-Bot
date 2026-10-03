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
package com.tg.escrow.chain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.ton.ton4j.cell.Cell;
import org.ton.ton4j.cell.CellSlice;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 紧急暂停 message body 编码（ET-21）的行为固定测试。
 *
 * <p><b>对拍口径</b>（见 {@link PauseMessageCodec} 类注释）：与合约侧（acton 的 PAUSE-0）
 * 独立断言同一组三要素——op 值 / 总位数 / 引用数。cell hash 是（bits, refs）的确定性函数，
 * 三要素同 ⇒ hash 必同。
 */
class PauseMessageCodecTest {

    @Test
    @DisplayName("pause body：裸操作码（32 bit / 0 refs / op 0x7d5a1015）")
    void pauseIsBareOpcode() {
        Cell body = PauseMessageCodec.pause();

        assertThat(body.getBits().getUsedBits()).isEqualTo(32);
        assertThat(body.getRefs()).isEmpty();
        assertThat(CellSlice.beginParse(body).loadUint(32))
                .isEqualTo(java.math.BigInteger.valueOf(0x7d5a1015L));
    }

    @Test
    @DisplayName("unpause body：裸操作码（32 bit / 0 refs / op 0x7d5a1016）")
    void unpauseIsBareOpcode() {
        Cell body = PauseMessageCodec.unpause();

        assertThat(body.getBits().getUsedBits()).isEqualTo(32);
        assertThat(body.getRefs()).isEmpty();
        assertThat(CellSlice.beginParse(body).loadUint(32))
                .isEqualTo(java.math.BigInteger.valueOf(0x7d5a1016L));
    }

    @Test
    @DisplayName("op 常量与 contracts/types.tolk 的 struct 标签逐字一致（0x7d5a1015/1016）")
    void opConstantsMatchContract() {
        assertThat(PauseMessageCodec.OP_PAUSE).isEqualTo(0x7d5a1015L);
        assertThat(PauseMessageCodec.OP_UNPAUSE).isEqualTo(0x7d5a1016L);
    }

    @Test
    @DisplayName("两个 body 互不相同（op 前缀即身份——不能编码成同一 cell）")
    void bodiesDiffer() {
        assertThat(PauseMessageCodec.pause().hash())
                .isNotEqualTo(PauseMessageCodec.unpause().hash());
    }
}
