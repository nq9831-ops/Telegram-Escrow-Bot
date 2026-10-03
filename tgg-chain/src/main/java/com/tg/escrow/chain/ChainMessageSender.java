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

/**
 * 写链发送接缝（升级编排 / S5 resolveDispute 共用），生产实现 {@link AdnlChainSender}。
 * 安全语义：必须经联邦钱包发送——合约授权判据是 {@code in.senderAddress == storage.federation}，
 * 裸外部消息直投合约不可行（本合约无 external 入口）。
 */
public interface ChainMessageSender {

    /** 经联邦钱包向目标合约发内部消息；组装/签名/广播任一失败抛——绝不假装已上链。 */
    void sendToContract(String contractAddress, Cell body, long valueNanoton)
            throws ChainUnavailableException;
}
