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
 * 推导托管合约自己的 jetton 钱包地址——**这是写入合约 storage 的信任锚点**。
 *
 * <p>为什么谨慎到近乎偏执：合约侧（见 contracts/types.tolk 的 EscrowExtra 注释）只认
 * 「由我方 jetton 钱包发来的 transfer_notification」。这个锚点一旦写错，
 * **合约不会崩溃、不会报错**，只会永远收不到合法入金——静默失效是最坏的失败形态。
 * 所以本类的职责不是「把节点的回复转出去」，而是**判定这个回复能不能当锚点用**：
 *
 * <ul>
 *   <li>空 / 空白 → 拒绝（等于没写锚点）；</li>
 *   <li>形态非法（非 36 字节 friendly 形式）→ 拒绝；</li>
 *   <li><b>全零地址</b> → 拒绝：它语法合法、但没有任何持有者会匹配它，
 *       是"看起来配好了其实永远收不到钱"的典型陷阱。</li>
 * </ul>
 *
 * <p>拒绝的方式一律是抛 {@link ChainUnavailableException}——**让部署失败**，
 * 而不是把一个坏锚点带进链上。
 */
public final class JettonWalletAddressResolver {

    private final JettonWalletQuery query;

    public JettonWalletAddressResolver(JettonWalletQuery query) {
        this.query = query;
    }

    /**
     * 推导并校验地址。
     *
     * @return 可信的 jetton 钱包地址
     * @throws ChainUnavailableException 查询不可用，或回复不能作为信任锚点
     */
    public String resolve(String jettonMaster, String ownerAddress) throws ChainUnavailableException {
        String reply = query.queryWalletAddress(jettonMaster, ownerAddress);
        requireUsableAnchor(reply);
        return reply;
    }

    private static void requireUsableAnchor(String reply) {
        // 判据统一在 TonAddresses（同一规则也被配置项复用，避免两处漂移）
        if (!TonAddresses.isUsableAsAnchor(reply)) {
            throw new ChainUnavailableException(
                    "推导出的 jetton 钱包地址不可作信任锚点（空 / 形态非法 / 全零）：" + reply);
        }
    }
}
