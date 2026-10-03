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
 * 「向链上问 jetton 钱包地址」的接缝——查询与判定分离，判定逻辑可离线钉住。
 * 契约：拿不到结果抛 {@link ChainUnavailableException}，不得返回空串/零地址/缓存值。
 */
@FunctionalInterface
public interface JettonWalletQuery {

    /** 问 master+owner 的 jetton 钱包地址；不可用抛 {@link ChainUnavailableException}。 */
    String queryWalletAddress(String jettonMaster, String ownerAddress) throws ChainUnavailableException;
}
