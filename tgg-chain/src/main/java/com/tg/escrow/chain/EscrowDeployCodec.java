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

import org.ton.ton4j.address.Address;
import org.ton.ton4j.cell.Cell;
import org.ton.ton4j.cell.CellBuilder;
import org.ton.ton4j.tlb.ActionSendMsg;
import org.ton.ton4j.tlb.CurrencyCollection;
import org.ton.ton4j.tlb.InternalMessageInfoRelaxed;
import org.ton.ton4j.tlb.MessageRelaxed;
import org.ton.ton4j.tlb.MsgAddressIntStd;
import org.ton.ton4j.tlb.OutList;
import org.ton.ton4j.tlb.StateInit;
import org.ton.ton4j.smartcontract.types.WalletV5InnerRequest;

import java.math.BigInteger;
import java.util.List;

/**
 * 托管合约部署的编码（S5 部署，2026-10-02）。
 *
 * <h2>部署 = 向「H(code, data) 派生的地址」发一条带 StateInit 的消息</h2>
 * <p>TON 上"部署"没有独立事务类型：向一个尚无代码的地址发送<b>携带 StateInit</b> 的内部消息，
 * 网络即按 stateInit 初始化该账户。三个产物：
 * <ol>
 *   <li>{@link #stateInit(Cell, Cell)}——code（{@link EscrowCode} 资源）+ data（{@link EscrowStorageCodec}）；</li>
 *   <li>{@link #addressOf(StateInit)}——合约地址 = H(StateInit)，与 acton 的
 *       {@code AutoDeployAddress.calculateAddress} 对拍（单测固化向量）；</li>
 *   <li>{@link #deployInnerRequest(StateInit, BigInteger)}——钱包 v5 的 inner request：
 *       一个 {@code ActionSendMsg}（内部消息，dest=合约地址、value=部署燃料、<b>init=StateInit</b>）。</li>
 * </ol>
 *
 * <h2>与 ton4j 的对接点（为什么走 inner request 直组）</h2>
 * <p>ton4j 的高层发送路径（{@code Destination}）<b>没有 stateInit 字段</b>——它是给"向已存在
 * 合约发消息"设计的。但其 {@code createExternalTransferBody} 有一个公开的兜底分支：
 * {@code recipients == null} 时直接采用 {@code config.body} 作为 inner request——本类的产物
 * 正是从那个注入点进入（见 {@code AdnlChainSender.sendDeploy}）。
 * ActionSendMsg 的字段组装照抄 ton4j 自身的 {@code convertDestinationToOutAction}（bounce/
 * dstAddr/value 的序列化方式、mode 默认 3），保证与既有写链路径同构。
 *
 * <p>纯逻辑、无副作用、不触网——可离线测试（结构回读 + acton 地址对拍）。
 */
public final class EscrowDeployCodec {

    /** 部署消息的 send mode：3 = 付费分离 + 忽略失败（ton4j 高层路径同款默认）。 */
    private static final int DEPLOY_SEND_MODE = 3;

    private EscrowDeployCodec() {
    }

    /** StateInit = { code, data }（无 ticktock / library）。 */
    public static StateInit stateInit(Cell code, Cell data) {
        if (code == null || data == null) {
            throw new IllegalArgumentException("部署编码：code 与 data 均不可为空");
        }
        return StateInit.builder().code(code).data(data).build();
    }

    /** 合约地址（workchain 0）= H(StateInit)。 */
    public static Address addressOf(StateInit stateInit) {
        if (stateInit == null) {
            throw new IllegalArgumentException("部署编码：stateInit 不可为空");
        }
        return stateInit.getAddress();
    }

    /**
     * 钱包 v5 的 inner request：一个携带 StateInit 的部署动作。
     *
     * @param stateInit      合约 { code, data }
     * @param valueNano      随部署消息附带的 TON（合约初始余额；要够支付部署后自身开销）
     * @return inner request cell（供 {@code AdnlChainSender.sendDeploy} 注入钱包 v5 的 body）
     */
    public static Cell deployInnerRequest(StateInit stateInit, BigInteger valueNano) {
        if (valueNano == null || valueNano.signum() <= 0) {
            throw new IllegalArgumentException("部署编码：部署附着金额必须为正，实为 " + valueNano);
        }
        Address target = addressOf(stateInit);

        InternalMessageInfoRelaxed info = InternalMessageInfoRelaxed.builder()
                .bounce(true)
                .dstAddr(MsgAddressIntStd.builder()
                        .workchainId((byte) target.wc)
                        .address(target.toBigInteger())
                        .build())
                .value(CurrencyCollection.builder().coins(valueNano).build())
                .build();
        MessageRelaxed deployMessage = MessageRelaxed.builder()
                .info(info)
                .init(stateInit)   // ← 部署的全部意义：dest 尚无代码，靠 init 唤醒
                .body(CellBuilder.beginCell().endCell())   // 空消息体（部署/探测消息，合约 else 分支放行）
                .build();
        ActionSendMsg action = ActionSendMsg.builder()
                .mode(DEPLOY_SEND_MODE)
                .outMsg(deployMessage)
                .build();
        OutList outActions = OutList.builder().actions(List.of(action)).build();
        return WalletV5InnerRequest.builder()
                .outActions(outActions)
                .hasOtherActions(false)
                .build()
                .toCell();
    }
}
