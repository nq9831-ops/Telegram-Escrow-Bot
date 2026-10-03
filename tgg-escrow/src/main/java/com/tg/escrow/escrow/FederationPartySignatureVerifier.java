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

import com.tg.escrow.common.TggException;
import com.tg.escrow.escrow.EscrowMultiSigRule.Party;
import com.tg.escrow.federation.FederationKeyPair;

/**
 * 联邦仲裁节点的签名验签器——{@link PartySignatureVerifier} 的生产实现。
 *
 * <h2>它补的是什么缺口</h2>
 * <p>{@code tgg-escrow} 的 POM 一直声明着对 {@code tgg-federation} 的依赖，但生产代码
 * 从未引用过它——裁决链路里那个"验签器"只有测试里的 lambda 替身。结果是：
 * {@link EscrowMultiSigRule} 判定"必含联邦"的前提（签名<b>已</b>验过）在生产里无人兑现。
 *
 * <p>本类把这条线接上：联邦公钥来自配置，验签走 {@link FederationKeyPair}。
 *
 * <h2>为什么只认 {@link Party#FEDERATION}</h2>
 * <p>买方与卖方的签名由钱包产生（{@code ton_proof} / TON Connect），验证需要链上环境，
 * 属 {@code tgg-chain} 的职责。本类对这两方一律返回 {@code false}——
 * <b>不越权代验</b>。若这里图省事"都放行"，那么伪造的买卖方签名会被当成有效签名方
 * 参与裁决，而"必含联邦"的防串通意义也随之瓦解。
 *
 * <p>生产环境需要一个按参与方分发的组合验签器：联邦交给本类，买卖双方交给各自的验签实现。
 * 组合逻辑留在编排层，本类只负责自己那一方。
 */
public final class FederationPartySignatureVerifier implements PartySignatureVerifier {

    private final String federationPublicKeyBase64;

    /**
     * @param federationPublicKeyBase64 联邦节点的 Ed25519 公钥（Base64）
     * @throws TggException 未提供公钥——宁可在启动期失败，也不要静默拒掉所有联邦签名
     *                      （那种故障表现为"裁决永远不够签名"，很难归因到配置缺失）
     */
    public FederationPartySignatureVerifier(String federationPublicKeyBase64) {
        if (federationPublicKeyBase64 == null || federationPublicKeyBase64.isBlank()) {
            throw new TggException("未提供联邦节点公钥——无法验证联邦签名");
        }
        this.federationPublicKeyBase64 = federationPublicKeyBase64.trim();
    }

    @Override
    public boolean verify(Party party, byte[] payload, byte[] signature) {
        if (party != Party.FEDERATION) {
            return false;
        }
        return FederationKeyPair.verify(payload, signature, federationPublicKeyBase64);
    }
}
