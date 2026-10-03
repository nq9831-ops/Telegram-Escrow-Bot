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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ChainGateway} 的行为固定测试——全部离线，用替身 provider 驱动。
 *
 * <p>钉住三件此前没有任何测试执行过的事：① 未配置时<b>表现为「能力关闭」而不是让上下文起不来</b>；
 * ② 配置里的 {@code jetton-master} <b>真的被用在推导上</b>（不是校验完就丢）；
 * ③ 配置值带首尾空白时不被误判为非法。
 */
class ChainGatewayTest {

    private static final String MASTER = TestAddresses.valid();
    private static final String CONTRACT = TestAddresses.validOther();

    /** 替身 provider：记录收到的入参，返回预置回复。 */
    private static final class RecordingQuery implements JettonWalletQuery {
        String seenMaster;
        String seenOwner;
        String reply = MASTER;
        boolean fail;

        @Override
        public String queryWalletAddress(String jettonMaster, String ownerAddress)
                throws ChainUnavailableException {
            if (fail) {
                throw new ChainUnavailableException("模拟查询不可用");
            }
            this.seenMaster = jettonMaster;
            this.seenOwner = ownerAddress;
            return reply;
        }
    }

    private static ChainGateway gatewayWith(RecordingQuery query) {
        return ChainGateway.of(new ChainSettings(MASTER, true), query);
    }

    @Test
    @DisplayName("未配置 → enabled() 为 false，且 derive 抛 ChainUnavailableException（而非返回空锚点）")
    void disabledGatewayFailsClosed() {
        ChainGateway gateway = ChainGateway.fromOptional("  ", true);

        assertThat(gateway.enabled()).isFalse();
        assertThatThrownBy(() -> gateway.deriveOwnJettonWallet(CONTRACT))
                .isInstanceOf(ChainUnavailableException.class);
    }

    @Test
    @DisplayName("配了合法 master → enabled() 为 true（且 fromOptional 永不返回 null）")
    void configuredGatewayIsEnabled() {
        ChainGateway gateway = ChainGateway.fromOptional(MASTER, true);

        assertThat(gateway).isNotNull();
        assertThat(gateway.enabled()).isTrue();
    }

    @Test
    @DisplayName("推导真的用配置里的 jetton-master——它不是校验完就丢的摆设")
    void derivationUsesConfiguredMaster() throws Exception {
        RecordingQuery query = new RecordingQuery();

        String derived = gatewayWith(query).deriveOwnJettonWallet(CONTRACT);

        assertThat(query.seenMaster)
                .as("配置里的 master 必须作为查询入参被用上")
                .isEqualTo(MASTER);
        assertThat(query.seenOwner).isEqualTo(CONTRACT);
        assertThat(derived).isNotBlank();
    }

    @Test
    @DisplayName("配置值带首尾空白 → 照常工作（不把「多了个空格」误报成配置错误）")
    void surroundingWhitespaceIsTolerated() {
        ChainGateway gateway = ChainGateway.fromOptional("  " + MASTER + "\n", true);

        assertThat(gateway.enabled()).isTrue();
    }

    @Test
    @DisplayName("provider 不可用 → 抛 ChainUnavailableException，不降级返回空值")
    void unavailableProviderFailsClosed() {
        RecordingQuery query = new RecordingQuery();
        query.fail = true;

        assertThatThrownBy(() -> gatewayWith(query).deriveOwnJettonWallet(CONTRACT))
                .isInstanceOf(ChainUnavailableException.class);
    }

    @Test
    @DisplayName("provider 回复不可作锚点（全零地址）→ 抛，绝不把坏锚点带出去")
    void unusableAnchorIsRejected() {
        RecordingQuery query = new RecordingQuery();
        query.reply = "EQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAM9c";

        assertThatThrownBy(() -> gatewayWith(query).deriveOwnJettonWallet(CONTRACT))
                .isInstanceOf(ChainUnavailableException.class);
    }
}
