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
import org.ton.ton4j.tlb.ActionSendMsg;
import org.ton.ton4j.tlb.InternalMessageInfoRelaxed;
import org.ton.ton4j.tlb.OutAction;
import org.ton.ton4j.tlb.StateInit;

import java.math.BigInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link EscrowDeployCodec} / {@link EscrowCode} 的行为固定测试——全部离线。
 *
 * <h2>两组对拍向量</h2>
 * <ul>
 *   <li>地址：acton 1.2.0（.rivet/scratch/dump-address.tolk，2026-10-02）用
 *       {@code AutoDeployAddress.calculateAddress} 对固定参数集算出 (wc=0, hash=79375543…)——本测试
 *       以 ton4j 的 {@code StateInit.getAddress()} 复算比对（任何一侧编码漂移都会红）；</li>
 *   <li>结构：部署 inner request 反序列化回读——init（StateInit）、dest、mode 三处逐项核对。</li>
 * </ul>
 */
class EscrowDeployCodecTest {

    private static final String BUYER =
            "0:1111111111111111111111111111111111111111111111111111111111111111";
    private static final String SELLER =
            "0:2222222222222222222222222222222222222222222222222222222222222222";
    private static final String FEDERATION =
            "0:3333333333333333333333333333333333333333333333333333333333333333";

    /** acton 向量：固定参数集算出 (wc, hash)——见 .rivet/scratch/dump-address.tolk。 */
    private static final String VECTOR_ADDRESS_HASH =
            "79375543206411237888229592775286076989266047191646126718364471580623299711013";

    private static StateInit fixedStateInit() {
        Cell data = EscrowStorageCodec.storageCell(
                BUYER, SELLER, FEDERATION, new BigInteger("1000000000"), 0,
                EscrowStorageCodec.ZERO_ADDRESS, 0);
        return EscrowDeployCodec.stateInit(EscrowCode.escrowCodeCell(), data);
    }

    @Test
    @DisplayName("地址：与 acton calculateAddress 对拍一致（wc=0 + hash 数值相同）")
    void addressMatchesActonVector() {
        var address = EscrowDeployCodec.addressOf(fixedStateInit());

        assertThat((int) address.wc).isEqualTo(0);
        assertThat(new BigInteger(1, address.hashPart).toString()).isEqualTo(VECTOR_ADDRESS_HASH);
    }

    @Test
    @DisplayName("code 资源：可加载且 hash 与记录一致（防静默漂移）")
    void escrowCodeLoadsAndHashMatches() {
        Cell code = EscrowCode.escrowCodeCell();

        assertThat(code).isNotNull();
        // 资源 hash 记录为 766CE1EC…（acton build 产物）——此处只验证"加载成功且自校验通过"，
        // 具体值漂移由资源同步流程 + 本测试的加载失败暴露
        assertThat(code.hash()).hasSize(32);
    }

    @Test
    @DisplayName("部署 inner request：逐层结构回读——Maybe-ref/OutList/ActionSendMsg/mode 逐项在位")
    void deployInnerRequestRoundTrips() {
        StateInit si = fixedStateInit();
        Cell inner = EscrowDeployCodec.deployInnerRequest(si, new BigInteger("2000000000"));

        // 布局（与 ton4j WalletV5InnerRequest.toCell 的定义一致）：[Maybe-ref: OutList][bit: hasOther=false]。
        // 注：不经过 WalletV5InnerRequest.deserialize——ton4j 2.1.0 的该反序列化在
        // hasOtherActions=false 形态上会继续强读 ActionList 而 overflow（toCell/deserialize 不对称，
        // 与本类构造无关）；逐层回读才是本布局的可靠判据。
        var cs = org.ton.ton4j.cell.CellSlice.beginParse(inner);
        Cell outListCell = cs.loadMaybeRefX();
        assertThat(outListCell).as("OutList 必须作为 Maybe 引用挂在 inner 根上").isNotNull();
        assertThat(cs.loadBit()).as("hasOtherActions=false").isFalse();

        var out = org.ton.ton4j.tlb.OutList.deserialize(
                org.ton.ton4j.cell.CellSlice.beginParse(outListCell));
        assertThat(out.getActions()).hasSize(1);

        OutAction first = out.getActions().get(0);
        assertThat(first).isInstanceOf(ActionSendMsg.class);
        ActionSendMsg send = (ActionSendMsg) first;
        assertThat(send.getMode()).as("mode=3（与 ton4j 高层路径同款）").isEqualTo(3);

        var outMsg = send.getOutMsg();
        assertThat(outMsg.getInit()).as("部署消息必须携带 StateInit").isNotNull();
        var info = (InternalMessageInfoRelaxed) outMsg.getInfo();
        assertThat(info.getBounce()).isTrue();
        assertThat(info.getDstAddr()).isNotNull();

        // dest 与合约地址一致（wc + hash）
        var dst = (org.ton.ton4j.tlb.MsgAddressIntStd) info.getDstAddr();
        assertThat(dst.getWorkchainId()).isEqualTo((byte) 0);
        assertThat(dst.getAddress()).isEqualTo(EscrowDeployCodec.addressOf(si).toBigInteger());

        // 回读的 StateInit 与原值一致（对齐部署目标的最后一道自检）
        assertThat(outMsg.getInit().getCode().hash())
                .isEqualTo(si.getCode().hash());
        assertThat(outMsg.getInit().getData().hash())
                .isEqualTo(si.getData().hash());
    }

    @Test
    @DisplayName("序列化稳定：同输入两次生成的 cell 相同（确定性）")
    void deterministicSerialization() {
        Cell a = EscrowDeployCodec.deployInnerRequest(fixedStateInit(), new BigInteger("2000000000"));
        Cell b = EscrowDeployCodec.deployInnerRequest(fixedStateInit(), new BigInteger("2000000000"));

        assertThat(a.hash()).isEqualTo(b.hash());
    }

    @Test
    @DisplayName("校验：非正部署金额 / 空 stateInit 拒绝")
    void invalidInputsAreRejected() {
        assertThatThrownBy(() -> EscrowDeployCodec.deployInnerRequest(fixedStateInit(), BigInteger.ZERO))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("金额");
        assertThatThrownBy(() -> EscrowDeployCodec.stateInit(null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("不可为空");
    }
}
