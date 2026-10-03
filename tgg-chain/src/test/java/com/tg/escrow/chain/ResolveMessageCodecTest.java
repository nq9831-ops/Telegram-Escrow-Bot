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

import java.math.BigInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code ResolveMessage} 的 message body 编码（S5 裁决出款）。
 *
 * <h2>与 {@code contracts/types.tolk} 逐位对齐</h2>
 * <pre>
 * struct (0x7d5a1006) ResolveMessage {   // DISPUTED → RELEASED/REFUNDED（仅联邦）
 *     outcome: uint8                      // 0=RELEASE 放款卖方；1=REFUND 退款买方
 * }
 * </pre>
 * 布局 = {@code [opcode 32bit][outcome 8bit]}，与升级三消息同款（{@link UpgradeMessageCodec}）。
 *
 * <h2>本测试只做往返验证</h2>
 * <p>没做 Tolk 侧 hash 对拍——{@code UpgradeMessageCodecTest} 那套向量要重写临时 tolk 测试并跑
 * {@code ~/.acton/bin/acton test} 提取，本机没有该工具链。往返验证（我们写出去的位 == 按同一
 * struct 读回来的位）足以钉住布局；**真机链上核销仍属 B7-B9 待办**，不因本测试而提前勾掉。
 */
class ResolveMessageCodecTest {

    @Test
    @DisplayName("RELEASE → [op 0x7d5a1006][outcome 0]，与 types.tolk 的 struct 逐位一致")
    void releaseEncodesOutcomeZero() {
        Cell body = ResolveMessageCodec.resolve(true);

        CellSlice slice = CellSlice.beginParse(body);
        assertThat(slice.loadUint(32))
                .as("操作码必须与合约的 ResolveMessage 标签一致")
                .isEqualTo(BigInteger.valueOf(0x7d5a1006L));
        assertThat(slice.loadUint(8)).as("0 = RELEASE 放款卖方").isEqualTo(BigInteger.ZERO);
    }

    @Test
    @DisplayName("REFUND → outcome 1")
    void refundEncodesOutcomeOne() {
        CellSlice slice = CellSlice.beginParse(ResolveMessageCodec.resolve(false));

        assertThat(slice.loadUint(32)).isEqualTo(BigInteger.valueOf(0x7d5a1006L));
        assertThat(slice.loadUint(8)).as("1 = REFUND 退款买方").isEqualTo(BigInteger.ONE);
    }

    @Test
    @DisplayName("两种 outcome 出不同的 cell——不能被编码成同一条消息")
    void outcomesAreDistinguishable() {
        assertThat(ResolveMessageCodec.resolve(true))
                .as("放款与退款发同一条消息 = 裁决方向失效")
                .isNotEqualTo(ResolveMessageCodec.resolve(false));
    }
}
