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
import com.tg.escrow.chain.EscrowCode;
import com.tg.escrow.chain.EscrowDeployCodec;
import com.tg.escrow.chain.EscrowStorageCodec;
import com.tg.escrow.common.EscrowException;
import com.tg.escrow.common.TggException;
import com.tg.escrow.escrow.EscrowOrder;
import com.tg.escrow.escrow.EscrowOrderLookupPort;
import com.tg.escrow.escrow.EscrowOrderStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.time.Clock;

/**
 * S5 部署编排（每订单链上合约实例）：<b>算地址 → 先落库 → 发部署 →（jetton 单）发 SetJettonWallet</b>。
 *
 * <h2>为什么"先落库、后发送"</h2>
 * <p>一旦部署消息发出，链上就可能已经出现合约（不可撤回）。链下必须在此刻之前已有记录——
 * 顺序反过来会出现「链上有合约、链下查无此单」的孤儿，且无法补记（地址没有第二处可推）。
 * 发送失败时地址仍在库中：<b>同参数重试推导出同地址</b>，重发即可（合约已存在时
 * 携带 init 的消息被当作普通空消息处理，重复部署无害）。
 *
 * <h2>防改绑</h2>
 * <p>已有地址且与新参数推导不一致 → 拒绝。静默改绑会让链下记账指向另一个合约
 * （那里的资金与订单无关），是不可回滚的错位。
 *
 * <h2>jetton 单的两阶段 W</h2>
 * <p>{@code asset=1} 时部署后自动补发 {@code SetJettonWallet}：真实钱包地址由
 * {@link ChainGateway#deriveOwnJettonWallet} 按（master, 合约地址）推导。该步骤失败
 * （网络抖动等）时不吞——抛给调用方；重试整个 deploy() 会自愈（部署重发无害、set 重发
 * 幂等——合约要求"仅从零设置"，已设置时会被拒，调用方可据错误判断）。
 *
 * <h2>金额口径</h2>
 * <p>{@code chainAmountNano} 由调用方显式提供（链上最小单位），本服务<b>不做币种到最小单位的
 * 隐式换算</b>——jetton 的 decimals 因币种而异，静默换算错一位就是资金事故。
 */
public final class EscrowChainDeploymentService {

    private static final Logger log = LoggerFactory.getLogger(EscrowChainDeploymentService.class);

    /** 部署结果（供 admin 端点逐字段回执）。 */
    public record DeploymentResult(
            long orderId,
            String contractAddress,
            boolean deploySubmitted,
            boolean jettonWalletSet,
            String jettonWallet) {
    }

    private final EscrowOrderLookupPort lookup;
    private final EscrowOrderStore store;
    private final ChainGateway gateway;
    private final Clock clock;

    public EscrowChainDeploymentService(EscrowOrderLookupPort lookup, EscrowOrderStore store,
                                        ChainGateway gateway, Clock clock) {
        if (lookup == null || store == null || gateway == null || clock == null) {
            throw new TggException("部署编排：订单查询、订单存储、链上入口与时钟均不可为空");
        }
        this.lookup = lookup;
        this.store = store;
        this.gateway = gateway;
        this.clock = clock;
    }

    /**
     * 对指定订单执行链上部署（幂等可重试）。
     *
     * @param orderId           订单号（须已存在且未绑定其它链上地址）
     * @param buyerAddress      买方 TON 地址（合约 storage 的当事方之一）
     * @param sellerAddress     卖方 TON 地址
     * @param federationAddress 联邦地址（链上信任根：Resolve/SetJettonWallet/升级的唯一放行者）
     * @param asset             计价资产：0=TON、1=JETTON
     * @param chainAmountNano   托管金额（链上最小单位；显式提供，不做隐式换算）
     * @param deployValueNanoton 随部署附带的 TON（合约初始余额）
     */
    public DeploymentResult deploy(long orderId, String buyerAddress, String sellerAddress,
                                   String federationAddress, int asset,
                                   BigInteger chainAmountNano, long deployValueNanoton) {
        EscrowOrder order = lookup.byId(orderId)
                .orElseThrow(() -> new EscrowException("订单 #" + orderId + " 不存在"));

        // TON Connect 线（2026-10-02）：请求体缺省时从订单 V11 列解析（显式值优先，防"钱包扣错钱"）。
        // 联邦地址无库来源（部署者身份，非订单当事方）——仍必填。
        String buyer = firstNonBlank(buyerAddress, order.getBuyerTonAddress());
        if (buyer == null) {
            throw new EscrowException("部署：买方地址缺失（请求未提供且订单 #" + orderId + " 未绑定）");
        }
        String seller = firstNonBlank(sellerAddress, order.getSellerTonAddress());
        if (seller == null) {
            throw new EscrowException("部署：卖方地址缺失（请求未提供且订单 #" + orderId + " 未绑定）");
        }
        if (federationAddress == null || federationAddress.isBlank()) {
            throw new EscrowException("部署：联邦地址缺失（federationAddress 必填）");
        }
        BigInteger amount = chainAmountNano != null
                ? chainAmountNano
                : parseAmountFromOrder(order, orderId);

        var storage = EscrowStorageCodec.storageCell(buyer, seller,
                federationAddress, amount, 0, EscrowStorageCodec.ZERO_ADDRESS, asset);
        var stateInit = EscrowDeployCodec.stateInit(EscrowCode.escrowCodeCell(), storage);
        String address = EscrowDeployCodec.addressOf(stateInit).toString();

        String existing = order.getChainContractAddress();
        if (existing == null) {
            // 回填 V11：本次实际使用的地址与金额沉淀到订单（部署前可更新的语义见实体注释）——
            // TON Connect 页只需让用户连接钱包，部署端的缺省值即可全部从库取
            order.attachBuyerTonAddress(buyer, clock.instant());
            order.attachSellerTonAddress(seller, clock.instant());
            order.attachChainAmount(amount.toString(), clock.instant());
            order.attachChainContractAddress(address, clock.instant());
            store.save(order);
            log.info("链上部署：订单 #{} 绑定合约地址 {}（先落库，随后发送部署消息）", orderId, address);
        } else if (!existing.equals(address)) {
            throw new EscrowException("订单 #" + orderId + " 已绑定链上合约 " + existing
                    + "——本次参数推导出 " + address + "，拒绝改绑（请核对订单与地址参数）");
        } else {
            log.info("链上部署：订单 #{} 重试发送（地址 {} 已在库，同参数幂等）", orderId, address);
        }

        gateway.deployEscrow(address, stateInit, deployValueNanoton);

        boolean jettonWalletSet = false;
        String jettonWallet = null;
        if (asset == 1) {
            jettonWallet = gateway.deriveOwnJettonWallet(address);
            gateway.setOwnJettonWallet(address, jettonWallet);
            jettonWalletSet = true;
            log.info("链上部署：订单 #{} 已补发 SetJettonWallet（{}）", orderId, jettonWallet);
        }
        return new DeploymentResult(orderId, address, true, jettonWalletSet, jettonWallet);
    }

    /** 显式值优先、库值为缺省；两者皆无返回 null（调用方抛明确错误）。 */
    private static String firstNonBlank(String explicit, String fallback) {
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        return (fallback != null && !fallback.isBlank()) ? fallback : null;
    }

    /** 从订单 V11 列解析托管金额（nanoton 十进制串）；缺省/非法时抛明确错误。 */
    private static BigInteger parseAmountFromOrder(EscrowOrder order, long orderId) {
        String stored = order.getChainAmountNano();
        if (stored == null || stored.isBlank()) {
            throw new EscrowException("部署：托管金额缺失（请求未提供且订单 #" + orderId + " 未绑定）");
        }
        try {
            return new BigInteger(stored);
        } catch (NumberFormatException ex) {
            throw new EscrowException("部署：订单 #" + orderId + " 的库中金额非法：" + stored);
        }
    }
}
