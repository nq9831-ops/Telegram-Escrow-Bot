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
import org.ton.ton4j.tlb.VmStackValue;
import org.ton.ton4j.tlb.VmStackValueInt;

import java.math.BigInteger;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AdnlUpgradeStatusQuery#statusFromValues} 的栈序回归钉（B9 真机定案，2026-10-02）。
 *
 * <p>真机证据（{@code S5LiveJettonUpgradeProbeTest}）：propose 后 {@code upgradeStatus} 读回
 * {@code tos[0] = 766CE1EC…} 对应的 256 位大数、{@code tos[1] = 1790940921}（发送时刻的 unix 秒）。
 * 原推定「后返回值在栈顶」（tos[0]=proposedAt）被证伪——本用例把定案口径钉住，口径回退即红。
 */
class AdnlUpgradeStatusQueryTest {

    @Test
    @DisplayName("栈序（B9 定案）：tos[0]=codeHash（256 位）、tos[1]=proposedAt（unix 秒）")
    void stackOrderMatchesLiveFinding() throws Exception {
        BigInteger codeHash = new BigInteger(
                "766ce1ece3a16af6e18ab385c487b28530f48b222a5d047654681ae4c9ccdae3", 16);
        long proposedAt = 1790940921L;
        List<VmStackValue> tos = List.of(
                VmStackValueInt.builder().value(codeHash).build(),
                VmStackValueInt.builder().value(BigInteger.valueOf(proposedAt)).build());

        UpgradeStatus status = AdnlUpgradeStatusQuery.statusFromValues(tos);

        assertThat(status.codeHash()).isEqualTo(codeHash);
        assertThat(status.proposedAt()).isEqualTo(proposedAt);
    }

    @Test
    @DisplayName("无提案 (0,0)：两口径同值，但解析不炸（交叉校验形态）")
    void zeroStatusParses() throws Exception {
        List<VmStackValue> tos = List.of(
                VmStackValueInt.builder().value(BigInteger.ZERO).build(),
                VmStackValueInt.builder().value(BigInteger.ZERO).build());

        UpgradeStatus status = AdnlUpgradeStatusQuery.statusFromValues(tos);

        assertThat(status.codeHash()).isEqualTo(BigInteger.ZERO);
        assertThat(status.proposedAt()).isZero();
    }
}
