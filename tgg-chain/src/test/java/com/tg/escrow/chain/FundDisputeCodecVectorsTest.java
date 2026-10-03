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

/**
 * Fund/Dispute codec 的跨语言对拍（acton 向量固化）。
 *
 * <p>向量来源：{@code .rivet/scratch/dump-fund-dispute-hash.tolk}（2026-10-02 产出）——
 * 两者均为纯操作码（32 位无字段消息）。
 */
class FundDisputeCodecVectorsTest {

    private static final String FUND_HASH =
            "10105837087138733252905019014294695121106929230250023611643626304554765099486";
    private static final String DISPUTE_HASH =
            "90982968693927192980519013890494528222473699926716275168402669133241442835920";

    @Test
    @DisplayName("Fund：32 位消息体 + hash 与 acton 全等")
    void fundVectorMatchesActon() {
        Cell cell = FundMessageCodec.fund();
        assertThat(cell.getBits().getUsedBits()).isEqualTo(32);
        assertThat(new BigInteger(1, cell.hash()).toString()).isEqualTo(FUND_HASH);
    }

    @Test
    @DisplayName("Dispute：32 位消息体 + hash 与 acton 全等")
    void disputeVectorMatchesActon() {
        Cell cell = DisputeMessageCodec.dispute();
        assertThat(cell.getBits().getUsedBits()).isEqualTo(32);
        assertThat(new BigInteger(1, cell.hash()).toString()).isEqualTo(DISPUTE_HASH);
    }
}
