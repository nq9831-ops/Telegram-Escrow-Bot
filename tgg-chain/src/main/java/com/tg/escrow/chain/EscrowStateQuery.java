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

/**
 * 合约链上状态的读取接缝（ET-19「链上事件监听」的读面）——{@code get fun escrowState()}。
 *
 * <p>与 {@link UpgradeStatusQuery} 同款模式：清晰接缝 + ADNL 实现 + 离线可测的纯解析。
 * 链下对账（订单登记 vs 链上事实）以它为唯一读面——「链上事件监听」在无调度器架构下的
 * 落点：由对账动作主动查，而不是被动收推送。
 */
public interface EscrowStateQuery {

    /**
     * 读合约链上状态。
     *
     * @param contractAddress 托管合约地址
     * @return 0=OPEN 1=LOCKED 2=DELIVERED 3=DISPUTED 4=RELEASED 5=REFUNDED
     * @throws ChainUnavailableException 源不可用 / 退出码非 0 / 返回栈不可解析
     */
    int queryState(String contractAddress) throws ChainUnavailableException;
}
