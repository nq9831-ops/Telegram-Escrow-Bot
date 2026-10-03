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
 * 争议裁决出款消息（{@code Resolve}）的 message body 编码（S5 · 方案 A）。
 *
 * <h2>与 contracts/types.tolk 逐位对齐</h2>
 * <pre>
 * struct (0x7d5a1006) ResolveMessage {   // DISPUTED → RELEASED/REFUNDED（仅联邦）
 *     outcome: uint8                      // 0=RELEASE 放款卖方；1=REFUND 退款买方
 * }
 * </pre>
 * 布局 = {@code [opcode 32bit][outcome 8bit]}——与升级三消息同款（{@link UpgradeMessageCodec}），
 * 同样走 {@link ChainMessageSender} 由<b>联邦钱包</b>发出（合约侧授权判据是
 * {@code in.senderAddress == storage.federation}；本合约刻意没有 external 入口）。
 *
 * <p><b>链下裁决 ≠ 链上出款</b>：本类只负责把"裁决方向"编成消息；是否真发出去由
 * {@link ChainGateway#resolveDispute} 决定，它未接线时照旧 fail-closed（绝不假装出款）。
 *
 * <p>纯逻辑、无副作用、不触网——可离线测试。
 */
public final class ResolveMessageCodec {

    /** {@code ResolveMessage} 操作码（contracts/types.tolk 的 struct 标签 0x7d5a1006）。 */
    public static final long OP_RESOLVE = 0x7d5a1006L;

    /** outcome = RELEASE（放款卖方）。 */
    private static final long OUTCOME_RELEASE = 0L;

    /** outcome = REFUND（退款买方）。 */
    private static final long OUTCOME_REFUND = 1L;

    private ResolveMessageCodec() {
    }

    /**
     * {@code ResolveMessage} body：[opcode][outcome]。
     *
     * @param release {@code true} = RELEASE 放款卖方；{@code false} = REFUND 退款买方
     * @return 消息体 cell
     */
    public static Cell resolve(boolean release) {
        return CellBuilder.beginCell()
                .storeUint(OP_RESOLVE, 32)
                .storeUint(release ? OUTCOME_RELEASE : OUTCOME_REFUND, 8)
                .endCell();
    }
}
