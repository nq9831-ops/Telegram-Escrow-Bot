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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 交付证明提交（ET-52/53）的行为固定测试。
 *
 * <p>与合约 {@code handleConfirm} 的守卫<b>同构</b>：仅买方、仅 DELIVERED 状态可提交——
 * TON 异步乱序下「确认先于交付到达」也不得越级（ET-58 在 Java 侧的镜像）。哈希走
 * {@link EvidenceHasher}（加盐，ET-57）且可复算验证。
 */
class DeliveryProofSubmissionTest {

    private static final long BUYER = 1001L;
    private static final long SELLER = 2002L;
    private static final Instant T0 = Instant.parse("2026-09-23T00:00:00Z");

    private static EscrowOrder deliveredOrder() {
        EscrowOrder o = new EscrowOrder(BUYER, SELLER, new BigDecimal("100"), "USDT", T0);
        o.markLocked(T0);
        o.markDelivered(T0);
        return o;
    }

    @Test
    @DisplayName("买方在 DELIVERED 状态提交 → 生成加盐哈希（可复算）")
    void buyerSubmitsProof() {
        DeliveryProofSubmission.Submission s =
                DeliveryProofSubmission.submit(deliveredOrder(), BUYER, "物流单号123", "salt");

        assertThat(s.proofHash()).isEqualTo(EvidenceHasher.saltedHash("物流单号123", "salt"));
        assertThat(s.orderId()).isNull(); // 未落库订单 id 可空，哈希与内容才是本体
    }

    @Test
    @DisplayName("非买方提交 → 拒绝（仅买方确认收货）")
    void onlyBuyer() {
        assertThatThrownBy(() -> DeliveryProofSubmission.submit(deliveredOrder(), SELLER, "x", "s"))
                .isInstanceOf(EscrowException.class);
    }

    @Test
    @DisplayName("非 DELIVERED 状态提交 → 拒绝（乱序安全：确认先于交付不得越级，ET-58）")
    void stateGuarded() {
        EscrowOrder locked = new EscrowOrder(BUYER, SELLER, new BigDecimal("100"), "USDT", T0);
        locked.markLocked(T0); // LOCKED 而非 DELIVERED

        assertThatThrownBy(() -> DeliveryProofSubmission.submit(locked, BUYER, "x", "s"))
                .isInstanceOf(EscrowException.class)
                .hasMessageContaining("DELIVERED");
    }

    @Test
    @DisplayName("证据/盐空白 → fail-closed（无盐哈希可反推，ET-57）")
    void blankInputsRejected() {
        assertThatThrownBy(() -> DeliveryProofSubmission.submit(deliveredOrder(), BUYER, "", "s"))
                .isInstanceOf(EscrowException.class);
        assertThatThrownBy(() -> DeliveryProofSubmission.submit(deliveredOrder(), BUYER, "x", ""))
                .isInstanceOf(EscrowException.class);
    }
}
