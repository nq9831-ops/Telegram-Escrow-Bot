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
 * 合约升级三消息的 message body 编码（⑥「合约升级入口」）。
 *
 * <h2>与 contracts/types.tolk 逐位对齐</h2>
 * <p>TL-B 序列化规则：32 位操作码前缀 + 字段（cell 字段序列化为引用）。三消息布局：
 * <ul>
 *   <li>{@code ProposeUpgradeMessage (0x7d5a1007) { newCode: cell }} → [opcode 32bit][ref newCode]</li>
 *   <li>{@code ApplyUpgradeMessage (0x7d5a1008) {}} → [opcode 32bit]</li>
 *   <li>{@code CancelUpgradeMessage (0x7d5a1009) {}} → [opcode 32bit]</li>
 * </ul>
 *
 * <h2>位级对拍（跨语言向量）</h2>
 * <p>{@code UpgradeMessageCodecTest} 用 Tolk 侧 {@code <消息>.toCell().hash()} 实际输出的
 * 向量（固定输入 {@code newCode = beginCell().storeUint(0xABCD, 16).endCell()}）钉住布局——
 * 两种构造必须出同一 hash。向量提取方法与坐标见测试类注释。
 *
 * <p>纯逻辑、无副作用、不触网——可离线测试。
 */
public final class UpgradeMessageCodec {

    /** ProposeUpgradeMessage 操作码（contracts/types.tolk:86 附近，struct 标签 0x7d5a1007）。 */
    public static final long OP_PROPOSE_UPGRADE = 0x7d5a1007L;

    /** ApplyUpgradeMessage 操作码（0x7d5a1008）。 */
    public static final long OP_APPLY_UPGRADE = 0x7d5a1008L;

    /** CancelUpgradeMessage 操作码（0x7d5a1009）。 */
    public static final long OP_CANCEL_UPGRADE = 0x7d5a1009L;

    private UpgradeMessageCodec() {
    }

    /**
     * ProposeUpgradeMessage body：[opcode][ref newCode]。
     *
     * @param newCode 待提案的新代码 cell（不可空——提案必须携带代码）
     * @return 消息体 cell
     */
    public static Cell proposeUpgrade(Cell newCode) {
        if (newCode == null) {
            throw new IllegalArgumentException("升级提案必须携带 newCode");
        }
        return CellBuilder.beginCell()
                .storeUint(OP_PROPOSE_UPGRADE, 32)
                .storeRef(newCode)
                .endCell();
    }

    /** ApplyUpgradeMessage body：[opcode]。 */
    public static Cell applyUpgrade() {
        return CellBuilder.beginCell().storeUint(OP_APPLY_UPGRADE, 32).endCell();
    }

    /** CancelUpgradeMessage body：[opcode]。 */
    public static Cell cancelUpgrade() {
        return CellBuilder.beginCell().storeUint(OP_CANCEL_UPGRADE, 32).endCell();
    }
}
