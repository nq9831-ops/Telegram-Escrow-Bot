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
 * 紧急暂停两消息的 message body 编码（ET-21「紧急暂停」）。
 *
 * <h2>与 contracts/types.tolk 逐位对齐</h2>
 * <p>TL-B 规则：32 位操作码前缀 + 空字段。两消息布局：
 * <ul>
 *   <li>{@code PauseMessage (0x7d5a1015) {}} → [opcode 32bit]</li>
 *   <li>{@code UnpauseMessage (0x7d5a1016) {}} → [opcode 32bit]</li>
 * </ul>
 *
 * <h2>对拍口径（与升级 codec 的差异及其理由）</h2>
 * <p>升级 codec 用 Tolk 侧 {@code toCell().hash()} 的字面量向量对拍。本 codec 采用
 * <b>结构三要素各自钉</b>：op 值、总位数（32）、引用数（0）——两侧（Java 测试 +
 * acton 的 PAUSE-0）独立断言同一组三要素。cell hash 是（bits, refs）的确定性函数，
 * 三要素同 ⇒ hash 必同——等价于 hash 字面量对拍，且少一处"复制哈希时抄错"的机会。
 *
 * <p>纯逻辑、无副作用、不触网——可离线测试。
 */
public final class PauseMessageCodec {

    /** PauseMessage 操作码（contracts/types.tolk，struct 标签 0x7d5a1015）。 */
    public static final long OP_PAUSE = 0x7d5a1015L;

    /** UnpauseMessage 操作码（0x7d5a1016）。 */
    public static final long OP_UNPAUSE = 0x7d5a1016L;

    private PauseMessageCodec() {
    }

    /** PauseMessage body：[opcode]。 */
    public static Cell pause() {
        return CellBuilder.beginCell().storeUint(OP_PAUSE, 32).endCell();
    }

    /** UnpauseMessage body：[opcode]。 */
    public static Cell unpause() {
        return CellBuilder.beginCell().storeUint(OP_UNPAUSE, 32).endCell();
    }
}
