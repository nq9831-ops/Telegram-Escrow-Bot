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

import com.tg.escrow.chain.ChainDeploySender;
import com.tg.escrow.chain.ChainGateway;
import com.tg.escrow.chain.ChainMessageSender;
import com.tg.escrow.chain.ChainSettings;
import com.tg.escrow.chain.ChainUnavailableException;
import com.tg.escrow.chain.JettonWalletQuery;
import com.tg.escrow.chain.UpgradeStatus;
import com.tg.escrow.chain.UpgradeStatusQuery;
import com.tg.escrow.escrow.EscrowOrder;
import com.tg.escrow.escrow.EscrowOrderLookupPort;
import com.tg.escrow.escrow.EscrowOrderStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.ton.ton4j.address.Address;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AdminChainController} 的行为固定测试——全部离线，用替身 provider 驱动，
 * <b>不连任何节点</b>。
 *
 * <p>它同时验证一件此前缺位的事：{@code ChainGateway.deriveOwnJettonWallet} 现在有了
 * <b>生产调用方</b>——「有一个入口能把它调到」不再只是类里的一个方法。
 */
class AdminChainControllerTest {

    private static final String MASTER = validAddress((byte) 0x11);
    private static final String CONTRACT = validAddress((byte) 0x22);
    private static final String WALLET = validAddress((byte) 0x33);

    private static String validAddress(byte fill) {
        byte[] hash = new byte[32];
        Arrays.fill(hash, fill);
        return Address.of(Address.BOUNCEABLE_TAG, 0, hash).toString(true, true, true);
    }

    /** 替身 provider：可指定回复或直接失败。 */
    private static final class StubQuery implements JettonWalletQuery {
        String reply = WALLET;
        boolean fail;
        String seenMaster;
        String seenOwner;

        @Override
        public String queryWalletAddress(String jettonMaster, String ownerAddress)
                throws ChainUnavailableException {
            if (fail) {
                throw new ChainUnavailableException("节点不可达");
            }
            this.seenMaster = jettonMaster;
            this.seenOwner = ownerAddress;
            return reply;
        }
    }

    /** 最小订单替身：lookup 与 store 共用一份内存表（部署编排的落库断言用）。 */
    private static final class OrdersStub implements EscrowOrderLookupPort, EscrowOrderStore {
        private final Map<Long, EscrowOrder> byId = new HashMap<>();

        EscrowOrder put(long id, EscrowOrder order) {
            order.assignId(id);
            byId.put(id, order);
            return order;
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

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-10-02T00:00:00Z"), ZoneOffset.UTC);

    private static final UpgradeStatusQuery NO_PROPOSAL =
            contract -> new UpgradeStatus(BigInteger.ZERO, 0L);

    private static AdminChainController controller(StubQuery query) {
        return controller(query, new OrdersStub(), new ArrayList<>(), null);
    }

    /**
     * @param deployed 非 null 时接上部署接缝（记录 "地址#value"）；null = 部署接缝未接线
     * @param msgSender 非 null 时接上写链接缝（记录消息体）；null = 写链栈未接线
     */
    private static AdminChainController controller(StubQuery query, OrdersStub orders,
                                                   List<String> deployed,
                                                   ChainMessageSender msgSender) {
        ChainGateway gateway = ChainGateway.of(new ChainSettings(MASTER, true), query);
        if (msgSender != null) {
            gateway = gateway.withUpgradeWriters(msgSender, NO_PROPOSAL);
        }
        if (deployed != null) {
            ChainDeploySender deploySender = (address, si, value) ->
                    deployed.add(address + "#" + value);
            gateway = gateway.withDeploySender(deploySender);
        }
        EscrowChainDeploymentService service =
                new EscrowChainDeploymentService(orders, orders, gateway, FIXED_CLOCK);
        return new AdminChainController(gateway, service);
    }

    @Test
    @DisplayName("链上能力未启用（未配 jetton-master）→ 503，明确说是本部署没开")
    void disabledCapabilityReturns503() {
        AdminChainController controller = disabledController();

        ResponseEntity<Map<String, Object>> resp = controller.ownJettonWallet(CONTRACT);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(resp.getBody()).containsEntry("ok", false);
        assertThat(String.valueOf(resp.getBody().get("error"))).contains("未启用");
    }

    /** 未启用链上能力的控制器（503 路径用；部署服务只是装配成形，本组用例不会调到它）。 */
    private static AdminChainController disabledController() {
        OrdersStub orders = new OrdersStub();
        return new AdminChainController(ChainGateway.fromOptional("  ", true),
                new EscrowChainDeploymentService(orders, orders,
                        ChainGateway.fromOptional("  ", true), FIXED_CLOCK));
    }

    @Test
    @DisplayName("推导成功 → 200 且回传 ownJettonWallet；推导用的是配置里的 master")
    void derivesOwnJettonWallet() {
        StubQuery query = new StubQuery();

        ResponseEntity<Map<String, Object>> resp = controller(query).ownJettonWallet(CONTRACT);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsEntry("ok", true);
        assertThat(resp.getBody()).containsEntry("ownJettonWallet", WALLET);
        assertThat(query.seenMaster).isEqualTo(MASTER);
        assertThat(query.seenOwner).as("owner 侧参数也要断言，否则错配测不出").isEqualTo(CONTRACT);
    }

    @Test
    @DisplayName("合约地址形态非法 → 400（是请求错，不是链不可用），且根本不去调 provider")
    void malformedContractReturns400() {
        StubQuery query = new StubQuery();

        ResponseEntity<Map<String, Object>> resp =
                controller(query).ownJettonWallet("not-an-address");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody()).containsEntry("ok", false);
        assertThat(query.seenMaster).as("形态非法应在触链之前就被挡下").isNull();
    }

    @Test
    @DisplayName("provider 不可用 → 502，绝不返回假地址")
    void unavailableProviderReturns502() {
        StubQuery query = new StubQuery();
        query.fail = true;

        ResponseEntity<Map<String, Object>> resp = controller(query).ownJettonWallet(CONTRACT);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(resp.getBody()).containsEntry("ok", false);
        assertThat(resp.getBody()).doesNotContainKey("ownJettonWallet");
    }

    @Test
    @DisplayName("provider 回复不可作信任锚点（全零地址）→ 502（判定层拦下，不放行坏锚点）")
    void unusableAnchorReturns502() {
        StubQuery query = new StubQuery();
        query.reply = "EQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAM9c";

        ResponseEntity<Map<String, Object>> resp = controller(query).ownJettonWallet(CONTRACT);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(resp.getBody()).doesNotContainKey("ownJettonWallet");
    }

    @Test
    @DisplayName("合约地址缺失/空白 → 400（请求本身不完整，与能力是否启用无关）")
    void blankContractReturns400() {
        StubQuery query = new StubQuery();

        assertThat(controller(query).ownJettonWallet("  ").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ---- S5 部署端点（POST /admin/chain/deploy）----

    private static final String BUYER = validAddress((byte) 0x44);
    private static final String SELLER = validAddress((byte) 0x55);
    private static final String FED = validAddress((byte) 0x66);

    private static AdminChainController.DeployRequest deployReq(long orderId, int asset,
                                                                String amount, long value) {
        return new AdminChainController.DeployRequest(orderId, BUYER, SELLER, FED,
                asset, amount, value);
    }

    @Test
    @DisplayName("部署：参数缺失/非法 → 400（订单号/资产/金额/附带金额/地址五组，全不触链）")
    void deployRejectsMalformedRequests() {
        AdminChainController c = controller(new StubQuery(), new OrdersStub(),
                new ArrayList<>(), null);

        assertThat(c.deploy(null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(c.deploy(deployReq(1L, 2, "1", 1L)).getStatusCode())
                .as("asset 非法").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(c.deploy(deployReq(1L, 0, "abc", 1L)).getStatusCode())
                .as("金额非整数").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(c.deploy(deployReq(1L, 0, "0", 1L)).getStatusCode())
                .as("金额非正").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(c.deploy(deployReq(1L, 0, "1", 0L)).getStatusCode())
                .as("附带金额非正").isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(c.deploy(new AdminChainController.DeployRequest(1L, null, SELLER, FED,
                0, "1", 1L)).getStatusCode())
                .as("地址缺失").isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("部署：订单不存在 → 400（业务拒绝，含原因），不触部署接缝")
    void deployMissingOrderReturns400() {
        List<String> deployed = new ArrayList<>();
        AdminChainController c = controller(new StubQuery(), new OrdersStub(), deployed, null);

        var resp = c.deploy(deployReq(999L, 0, "1000000000", 2_000_000_000L));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(resp.getBody().get("error"))).contains("不存在");
        assertThat(deployed).isEmpty();
    }

    @Test
    @DisplayName("部署 TON 单：200 + 先落库 + 部署接缝收到；jetton 补发跳过")
    void deployTonOrderSucceeds() {
        OrdersStub orders = new OrdersStub();
        orders.put(7L, new EscrowOrder(1001L, 2002L, new BigDecimal("1"), "TON",
                Instant.parse("2026-10-02T00:00:00Z")));
        List<String> deployed = new ArrayList<>();
        AdminChainController c = controller(new StubQuery(), orders, deployed, null);

        var resp = c.deploy(deployReq(7L, 0, "1000000000", 2_000_000_000L));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsEntry("ok", true);
        String address = String.valueOf(resp.getBody().get("contractAddress"));
        assertThat(address).isNotBlank();
        assertThat(deployed).as("部署接缝应收到一次发送").hasSize(1);
        assertThat(deployed.get(0)).startsWith(address);
        assertThat(resp.getBody()).containsEntry("jettonWalletSet", false);
        assertThat(orders.get(7L).getChainContractAddress()).as("先落库").isEqualTo(address);
    }

    @Test
    @DisplayName("部署 jetton 单：部署后补发 SetJettonWallet（经写链接缝，目标=合约地址）")
    void deployJettonOrderSetsOwnWallet() {
        OrdersStub orders = new OrdersStub();
        orders.put(7L, new EscrowOrder(1001L, 2002L, new BigDecimal("1"), "USDT",
                Instant.parse("2026-10-02T00:00:00Z")));
        List<String> deployed = new ArrayList<>();
        List<String> setTargets = new ArrayList<>();
        ChainMessageSender msgSender = (contract, body, value) -> setTargets.add(contract);
        AdminChainController c = controller(new StubQuery(), orders, deployed, msgSender);

        var resp = c.deploy(deployReq(7L, 1, "1000000", 2_000_000_000L));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsEntry("jettonWalletSet", true);
        assertThat(resp.getBody()).containsEntry("jettonWallet", WALLET);
        assertThat(setTargets).as("SetJettonWallet 发往合约地址").containsExactly(
                String.valueOf(resp.getBody().get("contractAddress")));
    }

    @Test
    @DisplayName("部署：接缝未接线 → 502；订单地址已落库（先落库语义，重试可续）")
    void deployUnwiredReturns502ButAddressPersisted() {
        OrdersStub orders = new OrdersStub();
        orders.put(7L, new EscrowOrder(1001L, 2002L, new BigDecimal("1"), "TON",
                Instant.parse("2026-10-02T00:00:00Z")));
        AdminChainController c = controller(new StubQuery(), orders, null, null);

        var resp = c.deploy(deployReq(7L, 0, "1000000000", 2_000_000_000L));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(String.valueOf(resp.getBody().get("error"))).contains("未接线");
        assertThat(orders.get(7L).getChainContractAddress())
                .as("发送失败但地址已落库——重试推导同地址").isNotNull();
    }

    @Test
    @DisplayName("构造：链上入口或部署编排为空 → 抛（装配错误不静默）")
    void nullCollaboratorsRejected() {
        OrdersStub orders = new OrdersStub();
        EscrowChainDeploymentService service = new EscrowChainDeploymentService(
                orders, orders, ChainGateway.fromOptional("  ", true), FIXED_CLOCK);
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> new AdminChainController(null, service)))
                .isInstanceOf(com.tg.escrow.common.TggException.class);
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> new AdminChainController(ChainGateway.fromOptional("  ", true), null)))
                .isInstanceOf(com.tg.escrow.common.TggException.class);
    }

    @Test
    @DisplayName("部署：订单存在但请求与库均无地址/金额 → 400（缺省读库后仍无值）")
    void deployWithoutAnyAddressSourceReturns400() {
        OrdersStub orders = new OrdersStub();
        orders.put(7L, new EscrowOrder(1001L, 2002L, new BigDecimal("1"), "TON",
                Instant.parse("2026-10-02T00:00:00Z")));
        AdminChainController c = controller(new StubQuery(), orders, new ArrayList<>(), null);

        var resp = c.deploy(new AdminChainController.DeployRequest(7L, null, null, FED,
                0, null, 1L));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(resp.getBody().get("error"))).contains("买方");
    }
}
