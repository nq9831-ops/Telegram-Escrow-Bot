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

import org.ton.ton4j.tlb.StateInit;

/**
 * 合约部署发送接缝（S5），生产实现 {@link AdnlChainSender}。
 * 与 {@link ChainMessageSender} 刻意分离：后者是"向已存在合约发消息"，部署是"向无代码地址投递
 * StateInit"——消息形态/约束/失败面不同，一接口一能力。
 */
@FunctionalInterface
public interface ChainDeploySender {

    /** 经联邦钱包发送携带 StateInit 的部署消息；组装/签名/广播任一失败抛——绝不假装已部署。 */
    void sendDeploy(String contractAddress, StateInit stateInit, long valueNanoton)
            throws ChainUnavailableException;
}
