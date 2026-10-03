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
package com.tg.escrow;

import com.tg.escrow.chain.ChainGateway;
import com.tg.escrow.chain.ChainSettings;
import com.tg.escrow.common.EscrowException;
import com.tg.escrow.escrow.EscrowOrder;
import com.tg.escrow.escrow.EscrowOrderLookupPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ChainEvidenceSink} 的边界固定测试（成功路径与"未部署"由
 * TradeCommandHandlerTest 的端到端用例覆盖；此处补三个早退边界）。
 */
class ChainEvidenceSinkTest {

    private static final Instant NOW = Instant.parse("2026-10-02T00:00:00Z");

    private static ChainGateway gateway() {
        return ChainGateway.of(
                new ChainSettings(validAddr((byte) 0x77), true),
                (master, owner) -> validAddr((byte) 0x77));
    }

    private static String validAddr(byte fill) {
        byte[] hash = new byte[32];
        java.util.Arrays.fill(hash, fill);
        return org.ton.ton4j.address.Address
                .of(org.ton.ton4j.address.Address.BOUNCEABLE_TAG, 0, hash)
                .toString(true, true, true);
    }

    private static EscrowOrderLookupPort lookup(boolean found, boolean withAddress) {
        EscrowOrder order = new EscrowOrder(1001L, 2002L, new BigDecimal("1"), "TON", NOW);
        order.assignId(1L);
        if (withAddress) {
            order.attachChainContractAddress(validAddr((byte) 0x99), NOW);
        }
        return new EscrowOrderLookupPort() {
            @Override
            public Optional<EscrowOrder> byId(long id) {
                return found ? Optional.of(order) : Optional.empty();
            }

            @Override
            public List<EscrowOrder> recentFor(long userId, int limit) {
                return List.of();
            }
        };
    }

    @Test
    @DisplayName("订单不存在 → EscrowException（如实抛，由双通道回落为「未成功」）")
    void missingOrderThrows() {
        ChainEvidenceSink sink = new ChainEvidenceSink(lookup(false, true), gateway(), "salt-16");

        assertThatThrownBy(() -> sink.save(1L, "证据"))
                .isInstanceOf(EscrowException.class).hasMessageContaining("不存在");
    }

    @Test
    @DisplayName("未部署链上合约 → EscrowException（含部署指引方向）")
    void missingAddressThrows() {
        ChainEvidenceSink sink = new ChainEvidenceSink(lookup(true, false), gateway(), "salt-16");

        assertThatThrownBy(() -> sink.save(1L, "证据"))
                .isInstanceOf(EscrowException.class).hasMessageContaining("未部署");
    }

    @Test
    @DisplayName("盐缺失 → EscrowException（无盐哈希可被字典反推，拒绝提交）")
    void missingSaltThrows() {
        ChainEvidenceSink sink = new ChainEvidenceSink(lookup(true, true), gateway(), "  ");

        assertThatThrownBy(() -> sink.save(1L, "证据"))
                .isInstanceOf(EscrowException.class).hasMessageContaining("盐");
    }
}
