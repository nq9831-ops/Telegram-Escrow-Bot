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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;
import org.ton.ton4j.cell.Cell;
import org.ton.ton4j.cell.CellBuilder;
import org.ton.ton4j.cell.CellSlice;

/**
 * {@link UpgradeMessageCodec} 的位级对拍——跨语言向量钉住与 contracts/types.tolk 的布局一致。
 *
 * <h2>向量来源（可复现）</h2>
 * <p>向量取自 <b>Tolk 侧</b> {@code <消息>.toCell().hash()} 的实际输出：临时测试
 * {@code tests/_vec.test.tolk}（固定输入 {@code newCode = beginCell().storeUint(0xABCD, 16).endCell()}）
 * 用 {@code expect(body.hash()).toEqual(0)} 把实际 hash 打进失败信息提取，取毕删除。
 * 复现：重写该临时测试跑一次 {@code ~/.acton/bin/acton test tests/_vec.test.tolk} 即得同值
 * （合约源码 contracts/types.tolk 未变动时哈希稳定）。
 */
class UpgradeMessageCodecTest {

    private static final String PROPOSE_HASH_DEC =
            "70685738977436858480682890535340436240704133120616751240301889129261477679884";
    private static final String APPLY_HASH_DEC =
            "88688884426597364290617596855110747135936111157197238154800268701557871243231";
    private static final String CANCEL_HASH_DEC =
            "70603014588148768089788280210226410123548464460602354908600968361750213263825";

    private static BigInteger hashOf(Cell cell) {
        return new BigInteger(1, cell.hash());
    }

    @Test
    void proposeBodyMatchesTolkVector() {
        Cell newCode = CellBuilder.beginCell().storeUint(0xABCD, 16).endCell();
        assertThat(hashOf(UpgradeMessageCodec.proposeUpgrade(newCode)))
                .isEqualTo(new BigInteger(PROPOSE_HASH_DEC));
    }

    @Test
    void applyBodyMatchesTolkVector() {
        assertThat(hashOf(UpgradeMessageCodec.applyUpgrade()))
                .isEqualTo(new BigInteger(APPLY_HASH_DEC));
    }

    @Test
    void cancelBodyMatchesTolkVector() {
        assertThat(hashOf(UpgradeMessageCodec.cancelUpgrade()))
                .isEqualTo(new BigInteger(CANCEL_HASH_DEC));
    }

    @Test
    void proposeBodyCarriesOpcodeAndNewCodeRef() {
        Cell newCode = CellBuilder.beginCell().storeUint(0xABCD, 16).endCell();
        Cell body = UpgradeMessageCodec.proposeUpgrade(newCode);
        // 布局：32 位 opcode + 1 个引用（newCode），与 ProposeUpgradeMessage 的 TL-B 一致
        assertThat(body.getBits().getUsedBits()).isEqualTo(32);
        assertThat(body.getRefs()).hasSize(1);
        assertThat(CellSlice.beginParse(body).loadUint(32))
                .isEqualTo(BigInteger.valueOf(UpgradeMessageCodec.OP_PROPOSE_UPGRADE));
    }

    @Test
    void applyAndCancelAreBareOpcodes() {
        for (Cell body : new Cell[] {UpgradeMessageCodec.applyUpgrade(),
                UpgradeMessageCodec.cancelUpgrade()}) {
            assertThat(body.getBits().getUsedBits()).isEqualTo(32);
            assertThat(body.getRefs()).isEmpty();
        }
    }

    @Test
    void proposeRejectsMissingCode() {
        assertThatThrownBy(() -> UpgradeMessageCodec.proposeUpgrade(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
