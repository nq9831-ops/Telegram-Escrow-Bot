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

import java.math.BigInteger;

/**
 * {@code RecordEvidence} 消息的 body 编码（ET-43 链上存证腿）。
 *
 * <h2>与 contracts/types.tolk 逐位对齐</h2>
 * <pre>
 * struct (0x7d5a1014) RecordEvidenceMessage {   // 买方/卖方/联邦可发
 *     evidenceHash: uint256                      // 链下 EvidenceHasher.saltedHash 的结果
 * }
 * </pre>
 * 布局 = {@code [opcode 32bit][hash 256bit]}——由单测对拍 acton 向量（288 bit）钉住。
 *
 * <p>合约侧以哈希为 key 存 map（value=提交时刻）：同哈希重复提交天然幂等。
 * 传哈希而非原文：链上只留证据指纹，原文留在链下双通道的另一腿。
 *
 * <p>纯逻辑、无副作用、不触网——可离线测试。
 */
public final class RecordEvidenceMessageCodec {

    /** {@code RecordEvidenceMessage} 操作码（contracts/types.tolk 的 struct 标签 0x7d5a1014）。 */
    public static final long OP_RECORD_EVIDENCE = 0x7d5a1014L;

    private RecordEvidenceMessageCodec() {
    }

    /**
     * {@code RecordEvidenceMessage} body：[opcode][evidenceHash 256bit]。
     *
     * @param evidenceHash 证据哈希（非负、< 2^256；链下由 saltedHash 转成大整数）
     * @return 消息体 cell
     */
    public static Cell record(BigInteger evidenceHash) {
        if (evidenceHash == null || evidenceHash.signum() < 0
                || evidenceHash.bitLength() > 256) {
            throw new IllegalArgumentException(
                    "RecordEvidence：证据哈希必须是 256 位非负整数，实为 " + evidenceHash);
        }
        return CellBuilder.beginCell()
                .storeUint(OP_RECORD_EVIDENCE, 32)
                .storeUint(evidenceHash, 256)
                .endCell();
    }
}
