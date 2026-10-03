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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.ton.ton4j.cell.Cell;
import org.ton.ton4j.cell.CellBuilder;
import org.ton.ton4j.cell.CellSlice;

/**
 * {@link ChainGateway} 升级三动作 + 提案跟踪的离线测试（⑥）——接缝全用替身（Recording 模式，
 * 同 ChainGatewayTest 惯例），不触网。
 *
 * <p>钉住：① 三动作各自发出正确 opcode 的消息体；② 未接线 fail-closed（恒抛
 * ChainUnavailableException，绝不假装已上链）；③ 提案跟踪透传查询接缝。
 */
class ChainGatewayUpgradeTest {

    private static final String CONTRACT = "EQCxE6mUtQJKFnGfaROTKOt1lZbDiiX1kCixRv7Nw2Id_sDs";

    private record Sent(String contract, Cell body, long value) {
    }

    private final List<Sent> sent = new ArrayList<>();
    private final ChainMessageSender recordingSender = (contract, body, value) ->
            sent.add(new Sent(contract, body, value));
    private final UpgradeStatus status = new UpgradeStatus(BigInteger.TEN, 1_700_000_000L);
    private final UpgradeStatusQuery recordingQuery = contract -> status;

    private ChainGateway wiredGateway() {
        return ChainGateway.of(
                        new ChainSettings("EQCxE6mUtQJKFnGfaROTKOt1lZbDiiX1kCixRv7Nw2Id_sDs", true),
                        (master, owner) -> "EQCxE6mUtQJKFnGfaROTKOt1lZbDiiX1kCixRv7Nw2Id_sDs")
                .withUpgradeWriters(recordingSender, recordingQuery);
    }

    @Test
    void proposeSendsProposeBodyWithFundingValue() throws Exception {
        Cell newCode = CellBuilder.beginCell().storeUint(0xABCD, 16).endCell();
        wiredGateway().proposeUpgrade(CONTRACT, newCode);

        assertThat(sent).hasSize(1);
        Sent s = sent.get(0);
        assertThat(s.contract()).isEqualTo(CONTRACT);
        assertThat(s.value()).isEqualTo(200_000_000L);
        assertThat(CellSlice.beginParse(s.body()).loadUint(32))
                .isEqualTo(BigInteger.valueOf(UpgradeMessageCodec.OP_PROPOSE_UPGRADE));
    }

    @Test
    void applyAndCancelSendTheirBodies() throws Exception {
        ChainGateway gateway = wiredGateway();
        gateway.applyUpgrade(CONTRACT);
        gateway.cancelUpgrade(CONTRACT);

        assertThat(sent).hasSize(2);
        assertThat(CellSlice.beginParse(sent.get(0).body()).loadUint(32))
                .isEqualTo(BigInteger.valueOf(UpgradeMessageCodec.OP_APPLY_UPGRADE));
        assertThat(CellSlice.beginParse(sent.get(1).body()).loadUint(32))
                .isEqualTo(BigInteger.valueOf(UpgradeMessageCodec.OP_CANCEL_UPGRADE));
    }

    @Test
    void upgradeStatusDelegatesToQuery() throws Exception {
        assertThat(wiredGateway().upgradeStatus(CONTRACT)).isSameAs(status);
    }

    @Test
    @DisplayName("resolveDispute 走写链接缝发 Resolve（不再恒抛）：RELEASE/REFUND 两种 outcome 各自编码")
    void resolveDisputeSendsResolveBodies() throws Exception {
        ChainGateway gateway = wiredGateway();
        gateway.resolveDispute(CONTRACT, true);
        gateway.resolveDispute(CONTRACT, false);

        assertThat(sent).hasSize(2);
        assertThat(sent.get(0).contract()).isEqualTo(CONTRACT);
        assertThat(sent.get(0).value()).as("与升级三消息同口径的随附值").isEqualTo(200_000_000L);

        CellSlice release = CellSlice.beginParse(sent.get(0).body());
        assertThat(release.loadUint(32))
                .isEqualTo(BigInteger.valueOf(ResolveMessageCodec.OP_RESOLVE));
        assertThat(release.loadUint(8)).as("RELEASE = 0").isEqualTo(BigInteger.ZERO);

        CellSlice refund = CellSlice.beginParse(sent.get(1).body());
        assertThat(refund.loadUint(32))
                .isEqualTo(BigInteger.valueOf(ResolveMessageCodec.OP_RESOLVE));
        assertThat(refund.loadUint(8)).as("REFUND = 1").isEqualTo(BigInteger.ONE);
    }

    @Test
    @DisplayName("缺合约地址 → fail-closed，且说明是「缺地址」而非「未接线」——null 交给发送器等于向空地址发钱")
    void resolveDisputeRejectsMissingContractAddress() {
        ChainGateway gateway = wiredGateway();

        assertThatThrownBy(() -> gateway.resolveDispute(null, true))
                .isInstanceOf(ChainUnavailableException.class)
                .hasMessageContaining("缺少托管合约地址");
        assertThatThrownBy(() -> gateway.resolveDispute("   ", true))
                .isInstanceOf(ChainUnavailableException.class);
        assertThat(sent).as("不得向空地址发出任何消息").isEmpty();
    }

    @Test
    @DisplayName("写链栈未接线时 resolveDispute 仍 fail-closed——绝不假装已出款")
    void unwiredGatewayFailsClosedOnResolve() {
        ChainGateway unwired = ChainGateway.of(
                new ChainSettings("EQCxE6mUtQJKFnGfaROTKOt1lZbDiiX1kCixRv7Nw2Id_sDs", true),
                (master, owner) -> "EQCxE6mUtQJKFnGfaROTKOt1lZbDiiX1kCixRv7Nw2Id_sDs");

        assertThatThrownBy(() -> unwired.resolveDispute(CONTRACT, true))
                .isInstanceOf(ChainUnavailableException.class)
                .hasMessageContaining("写链栈未接线");
    }

    @Test
    void unwiredGatewayFailsClosedOnAllUpgradeActions() {
        ChainGateway unwired = ChainGateway.of(
                new ChainSettings("EQCxE6mUtQJKFnGfaROTKOt1lZbDiiX1kCixRv7Nw2Id_sDs", true),
                (master, owner) -> "EQCxE6mUtQJKFnGfaROTKOt1lZbDiiX1kCixRv7Nw2Id_sDs");

        assertThatThrownBy(() -> unwired.proposeUpgrade(CONTRACT,
                CellBuilder.beginCell().endCell()))
                .isInstanceOf(ChainUnavailableException.class)
                .hasMessageContaining("写链栈未接线");
        assertThatThrownBy(() -> unwired.applyUpgrade(CONTRACT))
                .isInstanceOf(ChainUnavailableException.class);
        assertThatThrownBy(() -> unwired.cancelUpgrade(CONTRACT))
                .isInstanceOf(ChainUnavailableException.class);
        assertThatThrownBy(() -> unwired.upgradeStatus(CONTRACT))
                .isInstanceOf(ChainUnavailableException.class);
        assertThat(sent).isEmpty();
    }

    @Test
    void senderFailurePropagatesAsChainUnavailable() {
        ChainGateway gateway = ChainGateway.of(
                        new ChainSettings("EQCxE6mUtQJKFnGfaROTKOt1lZbDiiX1kCixRv7Nw2Id_sDs", true),
                        (master, owner) -> "EQCxE6mUtQJKFnGfaROTKOt1lZbDiiX1kCixRv7Nw2Id_sDs")
                .withUpgradeWriters((contract, body, value) -> {
                    throw new ChainUnavailableException("广播失败");
                }, recordingQuery);

        assertThatThrownBy(() -> gateway.applyUpgrade(CONTRACT))
                .isInstanceOf(ChainUnavailableException.class)
                .hasMessageContaining("广播失败");
    }
}
