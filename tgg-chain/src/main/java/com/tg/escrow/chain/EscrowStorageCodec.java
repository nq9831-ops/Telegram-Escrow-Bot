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

import java.math.BigInteger;

/**
 * EscrowStorage 初始化 cell 的编码（S5 部署，2026-10-02）。
 *
 * <h2>与 contracts/types.tolk 逐位对齐（跨语言对拍）</h2>
 * <pre>
 * struct EscrowStorage { buyer: address, seller: address, federation: address,
 *                        amount: coins, state: uint8, extra: Cell&lt;EscrowExtra&gt; }
 * struct EscrowExtra   { ownJettonWallet: address, asset: uint8,
 *                        upgrade: Cell&lt;UpgradeProposal&gt;?, evidence: map&lt;uint256,uint32&gt; }
 * </pre>
 * 布局由单测固化：acton script 输出位长与 hash 向量（{@code EscrowStorageCodecTest}）——
 * 任何一侧漂移先红在那里，而不是在真机部署后。
 *
 * <h2>零哨兵（两阶段 W 的第 1 步）</h2>
 * 部署时 {@code ownJettonWallet} 填零地址（与合约 {@code isZeroAddress} 同值）：
 * 合约地址 = H(code, data) 与 W = J(master, 合约地址) 是自引用方程（伪随机固定点迭代
 * 不收敛），真实值由部署后联邦经 {@code SetJettonWallet} 一次性写入（types.tolk 详述）。
 *
 * <p>纯逻辑、无副作用、不触网——可离线测试。
 */
public final class EscrowStorageCodec {

    /** 零地址哨兵（与合约 {@code isZeroAddress} 同值）：部署时 ownJettonWallet 的初始值。 */
    public static final String ZERO_ADDRESS =
            "0:0000000000000000000000000000000000000000000000000000000000000000";

    private EscrowStorageCodec() {
    }

    /**
     * EscrowExtra cell：W(267) + asset(8) + upgrade Maybe(1)=0 + evidence map Maybe(1)=0。
     * 空 map 与无提案各占 1 bit 标志——**恰 277 bit / 0 ref**（对拍测试钉住）。
     */
    public static Cell extraCell(String ownJettonWallet, int asset) {
        requireAsset(asset);
        return CellBuilder.beginCell()
                .storeAddress(parse(ownJettonWallet, "ownJettonWallet"))
                .storeUint(asset, 8)
                .storeBit(false)   // upgrade: Cell<UpgradeProposal>? = null（无提案）
                .storeBit(false)   // evidence: map<uint256,uint32> = 空（null）
                .endCell();
    }

    /**
     * EscrowStorage cell：buyer + seller + federation + amount(coins) + state(uint8) + extra 引用。
     *
     * @param buyerAddress      买方 TON 地址（friendly 或 raw 形态）
     * @param sellerAddress     卖方 TON 地址
     * @param federationAddress 联邦地址（链上信任根：Resolve/SetJettonWallet/升级的唯一放行者）
     * @param amountNano        托管金额（纳币；coins 编码须为正）
     * @param escrowState       初始状态（0=OPEN；部署只接受 OPEN——先于任何资金动作）
     * @param ownJettonWallet   我方 jetton 钱包（两阶段流程第 1 步传 {@link #ZERO_ADDRESS}）
     * @param asset             计价资产：0=TON、1=JETTON
     */
    public static Cell storageCell(String buyerAddress, String sellerAddress,
                                   String federationAddress, BigInteger amountNano,
                                   int escrowState, String ownJettonWallet, int asset) {
        if (amountNano == null || amountNano.signum() <= 0) {
            throw new IllegalArgumentException("部署编码：金额必须为正，实为 " + amountNano);
        }
        if (escrowState != 0) {
            // 部署即 OPEN：钱未托管、状态机从头开始——非 OPEN 的初始态没有业务含义
            throw new IllegalArgumentException("部署编码：初始状态只能是 OPEN(0)，实为 " + escrowState);
        }
        requireAsset(asset);
        return CellBuilder.beginCell()
                .storeAddress(parse(buyerAddress, "buyer"))
                .storeAddress(parse(sellerAddress, "seller"))
                .storeAddress(parse(federationAddress, "federation"))
                .storeCoins(amountNano)
                .storeUint(escrowState, 8)
                .storeRef(extraCell(ownJettonWallet, asset))
                .endCell();
    }

    private static void requireAsset(int asset) {
        if (asset != 0 && asset != 1) {
            throw new IllegalArgumentException("部署编码：资产只能是 0(TON) 或 1(JETTON)，实为 " + asset);
        }
    }

    private static Address parse(String rawOrFriendly, String field) {
        if (rawOrFriendly == null || rawOrFriendly.isBlank()) {
            throw new IllegalArgumentException("部署编码：" + field + " 地址未提供");
        }
        try {
            return Address.of(rawOrFriendly);
        } catch (Exception | Error e) {
            // ton4j 对非法输入可能抛裸 Error（AdnlJettonWalletQuery 同款坑）——一并兜住
            throw new IllegalArgumentException(
                    "部署编码：" + field + " 地址非法（" + rawOrFriendly + "）：" + e.getMessage(), e);
        }
    }
}
