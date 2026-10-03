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

import org.ton.ton4j.address.Address;
import org.ton.ton4j.cell.Cell;
import org.ton.ton4j.cell.CellBuilder;

/**
 * {@code SetJettonWallet} 消息的 body 编码（S5 部署 · 两阶段 W 的第 2 步）。
 *
 * <h2>与 contracts/types.tolk 逐位对齐</h2>
 * <pre>
 * struct (0x7d5a1013) SetJettonWalletMessage {   // 仅联邦、仅从零哨兵设置一次
 *     wallet: address                             // (master, 本合约地址) 链下推导的结果
 * }
 * </pre>
 * 布局 = {@code [opcode 32bit][address 267bit]}——由单测对拍 acton 向量（299 bit）钉住。
 *
 * <p>纯逻辑、无副作用、不触网——可离线测试。
 */
public final class SetJettonWalletMessageCodec {

    /** {@code SetJettonWalletMessage} 操作码（contracts/types.tolk 的 struct 标签 0x7d5a1013）。 */
    public static final long OP_SET_JETTON_WALLET = 0x7d5a1013L;

    private SetJettonWalletMessageCodec() {
    }

    /**
     * {@code SetJettonWalletMessage} body：[opcode][wallet address]。
     *
     * @param walletAddress 推导出的我方 jetton 钱包地址（friendly 或 raw）
     * @return 消息体 cell
     */
    public static Cell set(String walletAddress) {
        if (walletAddress == null || walletAddress.isBlank()) {
            throw new IllegalArgumentException("SetJettonWallet：钱包地址未提供");
        }
        Address wallet;
        try {
            wallet = Address.of(walletAddress);
        } catch (Exception | Error e) {
            // ton4j 对非法输入可能抛裸 Error——一并兜住（AdnlJettonWalletQuery 同款坑）
            throw new IllegalArgumentException(
                    "SetJettonWallet：钱包地址非法（" + walletAddress + "）：" + e.getMessage(), e);
        }
        return CellBuilder.beginCell()
                .storeUint(OP_SET_JETTON_WALLET, 32)
                .storeAddress(wallet)
                .endCell();
    }
}
