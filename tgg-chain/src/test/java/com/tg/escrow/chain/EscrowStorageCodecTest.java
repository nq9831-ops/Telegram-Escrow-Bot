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

import java.math.BigInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link EscrowStorageCodec} 的位级对拍测试——固化 acton script 输出的向量。
 *
 * <h2>对拍口径（跨语言）</h2>
 * <p>向量由 `.rivet/scratch/dump-storage-hash.tolk`（acton 1.2.0，2026-10-02）用
 * Tolk 编译器自身的 `toCell()` 产出：
 * <pre>
 * buyer=0:11…11(×32)  seller=0:22…22  federation=0:33…33
 * amount=1000000000   state=0          W=零哨兵  asset=0
 * </pre>
 * 任何一侧布局漂移，先红在这里——而不是在真机部署后。
 */
class EscrowStorageCodecTest {

    private static final String BUYER =
            "0:1111111111111111111111111111111111111111111111111111111111111111";
    private static final String SELLER =
            "0:2222222222222222222222222222222222222222222222222222222222222222";
    private static final String FEDERATION =
            "0:3333333333333333333333333333333333333333333333333333333333333333";

    /** acton script 输出：EscrowExtra 空提案/空 map 时 277 bit / 0 ref。 */
    private static final String VECTOR_EXTRA_HASH =
            "42150874334067120283996665712168066087194900779735962934127534986612323263823";
    /** acton script 输出：整 storage cell 845 bit / 1 ref。 */
    private static final String VECTOR_STORAGE_HASH =
            "18630540816612875914322894916142365722978431390297144183555032454525248691858";

    @Test
    @DisplayName("EscrowExtra：277 bit / 0 ref，hash 与 acton 向量一致")
    void extraCellMatchesActonVector() {
        Cell extra = EscrowStorageCodec.extraCell(EscrowStorageCodec.ZERO_ADDRESS, 0);

        assertThat(extra.getBits().getUsedBits()).as("位布局漂移会先红在这里").isEqualTo(277);
        assertThat(extra.getRefs()).isEmpty();
        assertThat(new BigInteger(1, extra.hash()).toString()).isEqualTo(VECTOR_EXTRA_HASH);
    }

    @Test
    @DisplayName("EscrowStorage：845 bit / 1 ref，hash 与 acton 向量一致")
    void storageCellMatchesActonVector() {
        Cell storage = EscrowStorageCodec.storageCell(
                BUYER, SELLER, FEDERATION, new BigInteger("1000000000"), 0,
                EscrowStorageCodec.ZERO_ADDRESS, 0);

        assertThat(storage.getBits().getUsedBits()).isEqualTo(845);
        assertThat(storage.getRefs()).hasSize(1);
        assertThat(new BigInteger(1, storage.hash()).toString()).isEqualTo(VECTOR_STORAGE_HASH);
    }

    @Test
    @DisplayName("校验：非正金额 / 非 OPEN 初态 / 非法资产 / 缺地址一律拒绝")
    void invalidInputsAreRejected() {
        assertThatThrownBy(() -> EscrowStorageCodec.storageCell(
                BUYER, SELLER, FEDERATION, BigInteger.ZERO, 0,
                EscrowStorageCodec.ZERO_ADDRESS, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("金额");
        assertThatThrownBy(() -> EscrowStorageCodec.storageCell(
                BUYER, SELLER, FEDERATION, BigInteger.ONE, 1,
                EscrowStorageCodec.ZERO_ADDRESS, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("OPEN");
        assertThatThrownBy(() -> EscrowStorageCodec.extraCell(EscrowStorageCodec.ZERO_ADDRESS, 2))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("资产");
        assertThatThrownBy(() -> EscrowStorageCodec.storageCell(
                "  ", SELLER, FEDERATION, BigInteger.ONE, 0,
                EscrowStorageCodec.ZERO_ADDRESS, 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("buyer");
    }
}
