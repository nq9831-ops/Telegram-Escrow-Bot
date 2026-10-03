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

import com.tg.escrow.chain.ChainUnavailableException;
import com.tg.escrow.chain.EscrowCode;
import com.tg.escrow.chain.EscrowDeployCodec;
import com.tg.escrow.chain.SetJettonWalletMessageCodec;
import com.tg.escrow.common.EscrowException;
import com.tg.escrow.escrow.EscrowOrder;
import com.tg.escrow.escrow.EscrowOrderLookupPort;
import com.tg.escrow.escrow.EscrowOrderStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link EscrowChainDeploymentService} 的行为固定测试——全部离线，替身驱动。
 *
 * <p>细粒度钉住：① 先落库、后发送的顺序语义（发送失败地址仍在库）；② 同参数重试幂等、
 * 异参数改绑拒绝；③ stateInit 的 code 就是资源里的合约 code（防"部署了别的版本"）；
 * ④ jetton 单的 SetJettonWallet body 与 codec 逐 hash 一致。
 */
class EscrowChainDeploymentServiceTest {

    private static final String BUYER =
            "0:1111111111111111111111111111111111111111111111111111111111111111";
    private static final String SELLER =
            "0:2222222222222222222222222222222222222222222222222222222222222222";
    private static final String FEDERATION =
            "0:3333333333333333333333333333333333333333333333333333333333333333";
    private static final String OTHER_BUYER =
            "0:9999999999999999999999999999999999999999999999999999999999999999";

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-10-02T00:00:00Z"), ZoneOffset.UTC);

    private static final class OrdersStub implements EscrowOrderLookupPort, EscrowOrderStore {
        private final Map<Long, EscrowOrder> byId = new HashMap<>();

        OrdersStub with(long id, EscrowOrder order) {
            order.assignId(id);
            byId.put(id, order);
            return this;
        }

        EscrowOrder get(long id) {
            return byId.get(id);
        }

        @Override
        public EscrowOrder save(EscrowOrder order) {
            byId.put(order.getId(), order);
            return order;
        }

        @Override
        public Optional<EscrowOrder> byId(long orderId) {
            return Optional.ofNullable(byId.get(orderId));
        }

        @Override
        public List<EscrowOrder> recentFor(long userId, int limit) {
            return List.of();
        }
    }

    private record DeployCall(String address, org.ton.ton4j.tlb.StateInit stateInit, long value) {
    }

    private record SendCall(String contract, org.ton.ton4j.cell.Cell body, long value) {
    }

    private final List<DeployCall> deploys = new ArrayList<>();
    private final List<SendCall> sends = new ArrayList<>();
    private boolean failDeploy;

    private EscrowChainDeploymentService service(OrdersStub orders) {
        var gateway = com.tg.escrow.chain.ChainGateway.of(
                        new com.tg.escrow.chain.ChainSettings(MASTER, true),
                        (master, owner) -> MASTER)
                .withDeploySender((address, si, value) -> {
                    if (failDeploy) {
                        throw new ChainUnavailableException("节点不可达");
                    }
                    deploys.add(new DeployCall(address, si, value));
                })
                .withUpgradeWriters((contract, body, value) -> sends.add(new SendCall(contract, body, value)),
                        contract -> new com.tg.escrow.chain.UpgradeStatus(BigInteger.ZERO, 0L));
        return new EscrowChainDeploymentService(orders, orders, gateway, FIXED_CLOCK);
    }

    /** 合法 friendly 地址（真 CRC——TonAddresses 判据会校验形态与 CRC，编字符串过不了）。 */
    private static String validAddress(byte fill) {
        byte[] hash = new byte[32];
        java.util.Arrays.fill(hash, fill);
        return org.ton.ton4j.address.Address
                .of(org.ton.ton4j.address.Address.BOUNCEABLE_TAG, 0, hash)
                .toString(true, true, true);
    }

    private static final String MASTER = validAddress((byte) 0x77);

    private static OrdersStub ordersWith(long id) {
        return new OrdersStub().with(id, new EscrowOrder(1001L, 2002L, new BigDecimal("1"),
                "TON", Instant.parse("2026-10-02T00:00:00Z")));
    }

    @Test
    @DisplayName("部署 TON 单：地址落库 + 部署接缝收到 stateInit（code 即资源合约 code）")
    void deployPersistsAndSends() {
        OrdersStub orders = ordersWith(7L);

        var result = service(orders).deploy(7L, BUYER, SELLER, FEDERATION, 0,
                new BigInteger("1000000000"), 2_000_000_000L);

        assertThat(result.contractAddress()).isNotBlank();
        assertThat(result.deploySubmitted()).isTrue();
        assertThat(result.jettonWalletSet()).isFalse();
        assertThat(orders.get(7L).getChainContractAddress()).isEqualTo(result.contractAddress());

        assertThat(deploys).hasSize(1);
        DeployCall call = deploys.get(0);
        assertThat(call.address()).isEqualTo(result.contractAddress());
        assertThat(call.value()).isEqualTo(2_000_000_000L);
        // code 是资源里的合约 code（不是别的版本）——逐 hash 对拍
        assertThat(call.stateInit().getCode().hash())
                .isEqualTo(EscrowCode.escrowCodeCell().hash());
        // 目标地址与 stateInit 推导一致（部署消息最终指向的地址）
        assertThat(EscrowDeployCodec.addressOf(call.stateInit()).toString())
                .isEqualTo(result.contractAddress());
    }

    @Test
    @DisplayName("同参数重试：幂等（不抛、不重复落库、可重发）")
    void retryWithSameParamsIsIdempotent() {
        OrdersStub orders = ordersWith(7L);
        EscrowChainDeploymentService svc = service(orders);

        var first = svc.deploy(7L, BUYER, SELLER, FEDERATION, 0,
                new BigInteger("1000000000"), 2_000_000_000L);
        var second = svc.deploy(7L, BUYER, SELLER, FEDERATION, 0,
                new BigInteger("1000000000"), 2_000_000_000L);

        assertThat(second.contractAddress()).isEqualTo(first.contractAddress());
        assertThat(deploys).as("两次调用两次发送（重发幂等，链上重复部署无害）").hasSize(2);
    }

    @Test
    @DisplayName("异参数改绑：拒绝且不发送（链下记账防错位）")
    void differentParamsRejected() {
        OrdersStub orders = ordersWith(7L);
        EscrowChainDeploymentService svc = service(orders);
        svc.deploy(7L, BUYER, SELLER, FEDERATION, 0, new BigInteger("1000000000"), 2_000_000_000L);

        assertThatThrownBy(() -> svc.deploy(7L, OTHER_BUYER, SELLER, FEDERATION, 0,
                new BigInteger("1000000000"), 2_000_000_000L))
                .isInstanceOf(EscrowException.class)
                .hasMessageContaining("拒绝改绑");
        assertThat(deploys).as("改绑被拒后不得再发").hasSize(1);
    }

    @Test
    @DisplayName("发送失败：地址已先落库 + 异常上抛（重试可续）")
    void failedSendLeavesPersistedAddress() {
        OrdersStub orders = ordersWith(7L);
        failDeploy = true;

        assertThatThrownBy(() -> service(orders).deploy(7L, BUYER, SELLER, FEDERATION, 0,
                new BigInteger("1000000000"), 2_000_000_000L))
                .isInstanceOf(ChainUnavailableException.class);

        assertThat(orders.get(7L).getChainContractAddress()).isNotNull();
        assertThat(deploys).isEmpty();
    }

    @Test
    @DisplayName("jetton 单：部署后补发 SetJettonWallet——body 与 codec 逐 hash 一致")
    void jettonOrderSetsOwnWalletWithEncodedBody() {
        OrdersStub orders = ordersWith(7L);

        var result = service(orders).deploy(7L, BUYER, SELLER, FEDERATION, 1,
                new BigInteger("1000000"), 2_000_000_000L);

        assertThat(result.jettonWalletSet()).isTrue();
        assertThat(result.jettonWallet()).isNotBlank();
        assertThat(sends).hasSize(1);
        SendCall set = sends.get(0);
        assertThat(set.contract()).isEqualTo(result.contractAddress());
        assertThat(set.body().hash())
                .isEqualTo(SetJettonWalletMessageCodec.set(result.jettonWallet()).hash());
    }

    @Test
    @DisplayName("缺省读库：地址与金额缺省时从订单 V11 列取（TON Connect 线）")
    void fallsBackToOrderV11Columns() {
        OrdersStub orders = ordersWith(7L);
        orders.get(7L).attachChainAmount("1000000000", FIXED_CLOCK.instant());
        orders.get(7L).attachBuyerTonAddress(validAddress((byte) 0x11), FIXED_CLOCK.instant());
        orders.get(7L).attachSellerTonAddress(validAddress((byte) 0x22), FIXED_CLOCK.instant());

        var result = service(orders).deploy(7L, null, null, FEDERATION, 0, null, 2_000_000_000L);

        assertThat(result.contractAddress()).isNotBlank();
        assertThat(deploys).hasSize(1);
    }

    @Test
    @DisplayName("缺省全缺（请求与库皆无）→ 明确拒绝，不触部署接缝")
    void rejectsWhenNeitherRequestNorOrderProvides() {
        OrdersStub orders = ordersWith(7L);

        assertThatThrownBy(() -> service(orders).deploy(7L, null, null, FEDERATION, 0, null,
                2_000_000_000L))
                .isInstanceOf(EscrowException.class).hasMessageContaining("买方");
        assertThat(deploys).isEmpty();
    }
}
