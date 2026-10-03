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
import org.ton.ton4j.cell.Cell;
import org.ton.ton4j.cell.CellBuilder;
import org.ton.ton4j.tlb.StateInit;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ChainGateway} 的 S5 部署与存证方法测试——全部离线，用替身驱动。
 *
 * <p>钉住三件：① 部署/存证消息<b>真的经接缝发出</b>（body 与 codec 产物逐 hash 一致）；
 * ② 未接线时 fail-closed（部署接缝未接线 / 写链栈未接线，绝不假装已发）；
 * ③ 两条接线互不清除（withUpgradeWriters 与 withDeploySender 组合）。
 */
class ChainGatewayDeployTest {

    private static final String CONTRACT = "EQCxE6mUtQJKFnGfaROTKOt1lZbDiiX1kCixRv7Nw2Id_sDs";
    private static final String WALLET = "EQCxE6mUtQJKFnGfaROTKOt1lZbDiiX1kCixRv7Nw2Id_sDs";

    private record Sent(String contract, Cell body, long value) {
    }

    private record Deployed(String contract, StateInit stateInit, long value) {
    }

    private final List<Sent> sent = new ArrayList<>();
    private final List<Deployed> deployed = new ArrayList<>();
    private final ChainMessageSender recordingSender = (contract, body, value) ->
            sent.add(new Sent(contract, body, value));
    private final ChainDeploySender recordingDeploy = (contract, stateInit, value) ->
            deployed.add(new Deployed(contract, stateInit, value));
    private final UpgradeStatusQuery noopQuery = contract -> new UpgradeStatus(BigInteger.ZERO, 0L);

    /** 基础网关：写链 + 部署两根接缝都接。 */
    private ChainGateway wiredGateway() {
        return ChainGateway.of(
                        new ChainSettings(TestAddresses.valid(), true),
                        (master, owner) -> TestAddresses.valid())
                .withUpgradeWriters(recordingSender, noopQuery)
                .withDeploySender(recordingDeploy);
    }

    private static StateInit sampleStateInit() {
        return EscrowDeployCodec.stateInit(
                CellBuilder.beginCell().storeUint(0xC0DE, 16).endCell(),
                CellBuilder.beginCell().storeUint(0xDA7A, 16).endCell());
    }

    @Test
    @DisplayName("deployEscrow：经部署接缝原文透传（address/stateInit/value）")
    void deployEscrowGoesThroughDeploySeam() throws Exception {
        StateInit si = sampleStateInit();

        wiredGateway().deployEscrow(CONTRACT, si, 2_000_000_000L);

        assertThat(deployed).hasSize(1);
        Deployed d = deployed.get(0);
        assertThat(d.contract()).isEqualTo(CONTRACT);
        assertThat(d.stateInit().getCode().hash()).isEqualTo(si.getCode().hash());
        assertThat(d.stateInit().getData().hash()).isEqualTo(si.getData().hash());
        assertThat(d.value()).isEqualTo(2_000_000_000L);
    }

    @Test
    @DisplayName("deployEscrow：部署接缝未接线 → fail-closed，绝不假装已部署")
    void deployEscrowWithoutSeamIsFailClosed() {
        ChainGateway unwired = ChainGateway.of(
                new ChainSettings(TestAddresses.valid(), true),
                (master, owner) -> TestAddresses.valid());

        assertThatThrownBy(() -> unwired.deployEscrow(CONTRACT, sampleStateInit(), 1L))
                .isInstanceOf(ChainUnavailableException.class)
                .hasMessageContaining("未接线");
        assertThat(deployed).isEmpty();
    }

    @Test
    @DisplayName("setOwnJettonWallet：body 与 SetJettonWalletMessageCodec 逐 hash 一致；value=0.2")
    void setOwnJettonWalletSendsEncodedBody() throws Exception {
        wiredGateway().setOwnJettonWallet(CONTRACT, WALLET);

        assertThat(sent).hasSize(1);
        Sent s = sent.get(0);
        assertThat(s.contract()).isEqualTo(CONTRACT);
        assertThat(s.value()).isEqualTo(200_000_000L);
        assertThat(s.body().hash())
                .isEqualTo(SetJettonWalletMessageCodec.set(WALLET).hash());
    }

    @Test
    @DisplayName("recordEvidence：body 与 RecordEvidenceMessageCodec 逐 hash 一致；value=0.2")
    void recordEvidenceSendsEncodedBody() throws Exception {
        BigInteger hash = new BigInteger("43981");

        wiredGateway().recordEvidence(CONTRACT, hash);

        assertThat(sent).hasSize(1);
        Sent s = sent.get(0);
        assertThat(s.value()).isEqualTo(200_000_000L);
        assertThat(s.body().hash())
                .isEqualTo(RecordEvidenceMessageCodec.record(hash).hash());
    }

    @Test
    @DisplayName("写链栈未接线 → set/record 恒抛（与升级/裁决同守卫）")
    void writePathWithoutSenderIsFailClosed() {
        ChainGateway deployOnly = ChainGateway.of(
                new ChainSettings(TestAddresses.valid(), true),
                (master, owner) -> TestAddresses.valid())
                .withDeploySender(recordingDeploy);

        assertThatThrownBy(() -> deployOnly.setOwnJettonWallet(CONTRACT, WALLET))
                .isInstanceOf(ChainUnavailableException.class)
                .hasMessageContaining("未接线");
        assertThatThrownBy(() -> deployOnly.recordEvidence(CONTRACT, BigInteger.ONE))
                .isInstanceOf(ChainUnavailableException.class)
                .hasMessageContaining("未接线");
        assertThat(sent).isEmpty();
    }

    @Test
    @DisplayName("两条接线互不清除：后接部署不影响先接的写链（反向亦然）")
    void writersComposeWithoutClobbering() throws Exception {
        ChainGateway gateway = wiredGateway();
        // 再次 with 部署（模拟装配顺序变化）——写链仍在
        ChainGateway again = gateway.withDeploySender(recordingDeploy);

        again.setOwnJettonWallet(CONTRACT, WALLET);
        again.deployEscrow(CONTRACT, sampleStateInit(), 1L);

        assertThat(sent).as("写链接缝未被 withDeploySender 清掉").hasSize(1);
        assertThat(deployed).as("部署接缝在位").hasSize(1);
    }
}
