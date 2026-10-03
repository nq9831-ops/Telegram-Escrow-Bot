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
 * Deliver/Confirm/Refund codec 的跨语言对拍（acton 向量固化）。
 *
 * <p>向量来源：{@code .rivet/scratch/dump-deliver-confirm-refund-hash.tolk}（2026-10-02）——
 * 三者均为纯操作码（32 位无字段消息）。op 写错与网络故障同表象，靠对拍前置拦下。
 */
class DeliverConfirmRefundCodecVectorsTest {

    private static final String DELIVER_HASH =
            "112004093580310286565364335314024609966015074527502936996568959047728230300858";
    private static final String CONFIRM_HASH =
            "108682704004646148354022788612618590289773209701179841943765916718173293297711";
    private static final String REFUND_HASH =
            "9916688178140031329882864456268683817395576734057845717902004002267345000423";

    @Test
    @DisplayName("Deliver：32 位消息体 + hash 与 acton 全等")
    void deliverVectorMatchesActon() {
        Cell cell = DeliverMessageCodec.deliver();
        assertThat(cell.getBits().getUsedBits()).isEqualTo(32);
        assertThat(new BigInteger(1, cell.hash()).toString()).isEqualTo(DELIVER_HASH);
    }

    @Test
    @DisplayName("Confirm：32 位消息体 + hash 与 acton 全等")
    void confirmVectorMatchesActon() {
        Cell cell = ConfirmMessageCodec.confirm();
        assertThat(cell.getBits().getUsedBits()).isEqualTo(32);
        assertThat(new BigInteger(1, cell.hash()).toString()).isEqualTo(CONFIRM_HASH);
    }

    @Test
    @DisplayName("Refund：32 位消息体 + hash 与 acton 全等")
    void refundVectorMatchesActon() {
        Cell cell = RefundMessageCodec.refund();
        assertThat(cell.getBits().getUsedBits()).isEqualTo(32);
        assertThat(new BigInteger(1, cell.hash()).toString()).isEqualTo(REFUND_HASH);
    }
}
