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
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 证据哈希（ET-51/57：链上存证 + 预映像防护）的行为固定测试。
 *
 * <p>交付证明哈希<b>必须加盐</b>（SPEC 6.2）：无盐哈希可被字典反推。两条钉住：
 * ①盐生效（不同盐不同哈希）；②盐空白 fail-closed（无盐调用等于没修）。
 */
class EvidenceHasherTest {

    @Test
    @DisplayName("盐生效：同证据不同盐 → 不同哈希（字典预计算失效）")
    void saltChangesHash() {
        String a = EvidenceHasher.saltedHash("单号123", "salt-A");
        String b = EvidenceHasher.saltedHash("单号123", "salt-B");

        assertThat(a).isNotEqualTo(b).hasSize(64); // SHA-256 hex
    }

    @Test
    @DisplayName("同盐同证据稳定（链上可复算验证）")
    void stableUnderSameSalt() {
        assertThat(EvidenceHasher.saltedHash("单号123", "s"))
                .isEqualTo(EvidenceHasher.saltedHash("单号123", "s"));
    }

    @Test
    @DisplayName("盐/证据空白 → fail-closed")
    void blankInputsRejected() {
        assertThatThrownBy(() -> EvidenceHasher.saltedHash("x", ""))
                .isInstanceOf(EscrowException.class);
        assertThatThrownBy(() -> EvidenceHasher.saltedHash(null, "s"))
                .isInstanceOf(EscrowException.class);
    }
}
