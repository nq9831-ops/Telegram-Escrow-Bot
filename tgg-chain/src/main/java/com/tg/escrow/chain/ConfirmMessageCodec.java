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

import org.ton.ton4j.cell.Cell;
import org.ton.ton4j.cell.CellBuilder;

/**
 * ConfirmMessage 消息体（contracts/types.tolk 的 {@code 0x7d5a1003}，<b>无字段</b>——纯操作码）。
 *
 * <p>买方确认收货（DELIVERED → RELEASED；出账全额给卖方）。
 *
 * <p>对拍：hash 向量由 acton 脚本（.rivet/scratch/dump-deliver-confirm-refund-hash.tolk）产出，
 * 固化于 DeliverConfirmRefundCodecVectorsTest——op 写错即红（未知 op 的消息与网络故障同表象，
 * 必须靠对拍在前置拦下）。
 */
public final class ConfirmMessageCodec {

    /** ConfirmMessage op（contracts/types.tolk）；与链上 switch 判据逐位一致。 */
    public static final long OP_CONFIRM = 0x7d5a1003L;

    private ConfirmMessageCodec() {
    }

    /** 消息体（仅操作码，32 位）。 */
    public static Cell confirm() {
        return CellBuilder.beginCell().storeUint(OP_CONFIRM, 32).endCell();
    }
}
