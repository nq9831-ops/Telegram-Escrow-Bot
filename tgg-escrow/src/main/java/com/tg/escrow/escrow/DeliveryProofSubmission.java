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
package com.tg.escrow.escrow;

import com.tg.escrow.common.EscrowException;

/**
 * 交付证明提交（ET-52/53）：买家确认收货时提交交付证明，生成<b>加盐</b>哈希供上链。
 *
 * <h2>与合约 {@code handleConfirm} 守卫同构（ET-58 的 Java 侧镜像）</h2>
 * <p>仅买方、仅 {@code DELIVERED} 状态可提交——TON 异步乱序下「确认先于交付到达」不得越级。
 * 哈希走 {@link EvidenceHasher#saltedHash}（加盐，ET-57 预映像防护），与合约侧
 * {@code hash(salt ++ evidence)} 约定一致、可复算验证。
 *
 * <p>纯函数。
 */
public final class DeliveryProofSubmission {

    /**
     * 提交结果。
     *
     * @param proofHash 加盐交付证明哈希（上链存证体）
     * @param orderId   订单 ID（未落库时为 {@code null}）
     */
    public record Submission(String proofHash, Long orderId) {
    }

    private DeliveryProofSubmission() {
    }

    /**
     * 提交付证明。
     *
     * @param order    订单（须处于 {@code DELIVERED}）
     * @param actorId  提交人（须为买方——确认收货是买方动作）
     * @param evidence 证据内容（如物流单号、IPFS 哈希；不得空白）
     * @param salt     盐值（<b>必填</b>——ET-57）
     */
    public static Submission submit(EscrowOrder order, long actorId, String evidence, String salt) {
        if (order == null) {
            throw new EscrowException("交付证明：订单不可为空");
        }
        if (actorId != order.getBuyerUserId()) {
            throw new EscrowException("交付证明：仅买方可确认收货并提交交付证明");
        }
        EscrowOrder.State state = order.currentState();
        if (state != EscrowOrder.State.DELIVERED) {
            throw new EscrowException("交付证明：仅 DELIVERED 状态可提交（当前 " + state
                    + "）——乱序到达的确认不得越级");
        }
        String proofHash = EvidenceHasher.saltedHash(evidence, salt);
        return new Submission(proofHash, order.getId());
    }
}
