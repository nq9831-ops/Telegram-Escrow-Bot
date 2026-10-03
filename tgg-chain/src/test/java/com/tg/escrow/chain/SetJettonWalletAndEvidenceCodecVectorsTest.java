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

import java.math.BigInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link SetJettonWalletMessageCodec} 与 {@link RecordEvidenceMessageCodec} 的位级对拍测试。
 *
 * <p>向量由 `.rivet/scratch/dump-storage-hash.tolk`（acton 1.2.0，2026-10-02）用
 * Tolk 编译器自身的 `toCell()` 产出：
 * <pre>
 * SET : wallet=0:44…44(×32)             → 299 bit（opcode 32 + address 267）
 * EV  : evidenceHash=43981（0xABCD）      → 288 bit（opcode 32 + hash 256）
 * </pre>
 */
class SetJettonWalletAndEvidenceCodecVectorsTest {

    private static final String WALLET =
            "0:4444444444444444444444444444444444444444444444444444444444444444";

    @Test
    @DisplayName("SetJettonWallet：299 bit / 0 ref，hash 与 acton 向量一致")
    void setJettonWalletMatchesActonVector() {
        var cell = SetJettonWalletMessageCodec.set(WALLET);

        assertThat(cell.getBits().getUsedBits()).isEqualTo(299);
        assertThat(cell.getRefs()).isEmpty();
        assertThat(new BigInteger(1, cell.hash()).toString())
                .isEqualTo("78030582713965496619291666603093908681249573926920973820401965692412367085377");
    }

    @Test
    @DisplayName("RecordEvidence：288 bit / 0 ref，hash 与 acton 向量一致")
    void recordEvidenceMatchesActonVector() {
        var cell = RecordEvidenceMessageCodec.record(new BigInteger("43981"));

        assertThat(cell.getBits().getUsedBits()).isEqualTo(288);
        assertThat(cell.getRefs()).isEmpty();
        assertThat(new BigInteger(1, cell.hash()).toString())
                .isEqualTo("68533521958044108435625108443930223336882759830616931028292148350983361104591");
    }

    @Test
    @DisplayName("校验：缺地址 / 非法地址 / 负哈希 / 超 256 位一律拒绝")
    void invalidInputsAreRejected() {
        assertThatThrownBy(() -> SetJettonWalletMessageCodec.set("  "))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("钱包地址");
        assertThatThrownBy(() -> SetJettonWalletMessageCodec.set("not-an-address"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("非法");
        assertThatThrownBy(() -> RecordEvidenceMessageCodec.record(new BigInteger("-1")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("256");
        assertThatThrownBy(() -> RecordEvidenceMessageCodec.record(BigInteger.ONE.shiftLeft(256)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("256");
    }
}
