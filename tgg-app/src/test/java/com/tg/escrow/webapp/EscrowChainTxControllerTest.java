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
package com.tg.escrow.webapp;

import com.tg.escrow.chain.DeliverMessageCodec;
import com.tg.escrow.chain.DisputeMessageCodec;
import com.tg.escrow.chain.FundMessageCodec;
import com.tg.escrow.escrow.EscrowOrder;
import com.tg.escrow.escrow.EscrowOrderLookupPort;
import com.tg.escrow.escrow.EscrowOrderStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.ton.ton4j.cell.Cell;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link EscrowChainTxController} 的行为固定测试（离线，替身驱动）。
 *
 * <p>钉住四类风险：① payload 与 codec 逐字节一致（BOC 解码后 hash 比对——"后端构造的钱包交易"
 * 不允许与核销过的 codec 有分毫出入）；② 角色矩阵负例（seller 发 FUND 必须 403——发出去也会被
 * 合约拒，早拒省一轮排查）；③ initData 篡改 401；④ 金额 = 链上额 + gas 余量（防"钱包扣错钱"）+
 * 地址为 TEP-2 friendly（raw 会被钱包拒）。
 */
class EscrowChainTxControllerTest {

    private static final String BOT_TOKEN = "123456:controller-test-token";
    private static final Instant T0 = Instant.parse("2026-10-02T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(T0, ZoneOffset.UTC);

    private static final long BUYER_ID = 1001L;
    private static final long SELLER_ID = 2002L;
    private static final long STRANGER_ID = 9999L;
    private static final long ORDER_ID = 7L;

    // ── initData 造数（按官方算法自算 hash，同 TradeInviteControllerTest 模式） ──

    private static String sign(String dcs) {
        try {
            Mac outer = Mac.getInstance("HmacSHA256");
            outer.init(new SecretKeySpec("WebAppData".getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"));
            byte[] secret = outer.doFinal(BOT_TOKEN.getBytes(StandardCharsets.UTF_8));
            Mac inner = Mac.getInstance("HmacSHA256");
            inner.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(inner.doFinal(dcs.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String initData(long userId) {
        // dcs 真机口径（2026-10-02 探针实证）：hash 按【解码态】值计算（串内保持 URL-encoded）。
        String userJson = "{\"id\":" + userId + "}";
        List<String> rawFields = new ArrayList<>(List.of(
                "auth_date=" + T0.getEpochSecond(),
                "user=" + URLEncoder.encode(userJson, StandardCharsets.UTF_8)));
        List<String> dcsLines = new ArrayList<>(List.of(
                "auth_date=" + T0.getEpochSecond(),
                "user=" + userJson));
        rawFields.sort(String::compareTo);
        dcsLines.sort(String::compareTo);
        return String.join("&", rawFields) + "&hash=" + sign(String.join("\n", dcsLines));
    }

    /** 合法 friendly 地址（真 CRC——TonAddresses/Address.of 判据会校验）。 */
    private static String validAddress(byte fill) {
        byte[] hash = new byte[32];
        java.util.Arrays.fill(hash, fill);
        return org.ton.ton4j.address.Address
                .of(org.ton.ton4j.address.Address.BOUNCEABLE_TAG, 0, hash)
                .toString(true, true, true);
    }

    private static final class Orders implements EscrowOrderLookupPort, EscrowOrderStore {
        private final Map<Long, EscrowOrder> map = new HashMap<>();

        Orders put(long id, EscrowOrder order) {
            order.assignId(id);
            map.put(id, order);
            return this;
        }

        EscrowOrder get(long id) {
            return map.get(id);
        }

        @Override
        public Optional<EscrowOrder> byId(long id) {
            return Optional.ofNullable(map.get(id));
        }

        @Override
        public EscrowOrder save(EscrowOrder order) {
            map.put(order.getId(), order);
            return order;
        }

        @Override
        public List<EscrowOrder> recentFor(long userId, int limit) {
            return List.of();
        }
    }

    /** 已部署 + 金额已绑定的订单（资金链前置齐备）。 */
    private static EscrowOrder deployedOrder() {
        EscrowOrder order = new EscrowOrder(BUYER_ID, SELLER_ID, new BigDecimal("1"),
                "TON", T0);
        order.attachChainContractAddress(validAddress((byte) 0x44), T0);
        order.attachChainAmount("1000000000", T0);
        return order;
    }

    private static EscrowChainTxController controller(Orders orders) {
        return new EscrowChainTxController(
                new WebAppInitDataVerifier(BOT_TOKEN, Duration.ofHours(1), CLOCK),
                orders, orders, true, "https://example.test", CLOCK);
    }

    @Test
    @DisplayName("FUND：payload 与 FundMessageCodec 逐字节一致、金额=链上额+余量、地址 kQ 形态")
    void fundPayloadMatchesCodecAndAmount() {
        Orders orders = new Orders().put(ORDER_ID, deployedOrder());

        var resp = controller(orders).chainTx(
                new EscrowChainTxController.ChainTxRequest(initData(BUYER_ID), ORDER_ID, "FUND"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = resp.getBody();
        assertThat(body).containsEntry("ok", true).containsEntry("network", "-3");

        byte[] decoded = Base64.getDecoder()
                .decode(String.valueOf(body.get("payload")));
        Cell payloadCell = Cell.fromBoc(decoded);
        assertThat(HexFormat.of().formatHex(payloadCell.hash()))
                .as("构造的 payload 必须与真机核销过的 codec 逐字节一致")
                .isEqualTo(HexFormat.of().formatHex(FundMessageCodec.fund().hash()));

        assertThat(String.valueOf(body.get("amount")))
                .isEqualTo(new BigInteger("1000000000")
                        .add(BigInteger.valueOf(
                                EscrowChainTxController.FUND_GAS_MARGIN_NANOTON)).toString());
        assertThat(String.valueOf(body.get("address"))).startsWith("kQ");
        assertThat((long) body.get("validUntil"))
                .isEqualTo(T0.getEpochSecond() + EscrowChainTxController.VALID_FOR_SECONDS);
    }

    @Test
    @DisplayName("角色矩阵：seller 发 FUND → 403（合约侧也会拒——早拒省一轮排查）")
    void sellerCannotFund() {
        Orders orders = new Orders().put(ORDER_ID, deployedOrder());

        var resp = controller(orders).chainTx(
                new EscrowChainTxController.ChainTxRequest(initData(SELLER_ID), ORDER_ID, "FUND"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("DELIVER 仅卖方；DISPUTE 当事方皆可且 payload 与 DisputeMessageCodec 一致")
    void sellerDeliversAndBothCanDispute() {
        Orders orders = new Orders().put(ORDER_ID, deployedOrder());

        var deliver = controller(orders).chainTx(
                new EscrowChainTxController.ChainTxRequest(initData(SELLER_ID), ORDER_ID, "DELIVER"));
        assertThat(deliver.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(HexFormat.of().formatHex(Cell.fromBoc(Base64.getDecoder()
                .decode(String.valueOf(deliver.getBody().get("payload")))).hash()))
                .isEqualTo(HexFormat.of().formatHex(DeliverMessageCodec.deliver().hash()));

        var dispute = controller(orders).chainTx(
                new EscrowChainTxController.ChainTxRequest(initData(BUYER_ID), ORDER_ID, "DISPUTE"));
        assertThat(dispute.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(HexFormat.of().formatHex(Cell.fromBoc(Base64.getDecoder()
                .decode(String.valueOf(dispute.getBody().get("payload")))).hash()))
                .isEqualTo(HexFormat.of().formatHex(DisputeMessageCodec.dispute().hash()));

        var strangerDispute = controller(orders).chainTx(
                new EscrowChainTxController.ChainTxRequest(initData(STRANGER_ID), ORDER_ID, "DISPUTE"));
        assertThat(strangerDispute.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("篡改 initData（hash 不符）→ 401")
    void tamperedInitDataRejected() {
        Orders orders = new Orders().put(ORDER_ID, deployedOrder());
        String tampered = initData(BUYER_ID).replaceAll("hash=[0-9a-f]+", "hash=" + "0".repeat(64));

        var resp = controller(orders).chainTx(
                new EscrowChainTxController.ChainTxRequest(tampered, ORDER_ID, "FUND"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("未部署订单（无合约地址）→ 400；未知动作 → 400")
    void undeployedOrderAndBadActionRejected() {
        EscrowOrder undeployed = new EscrowOrder(BUYER_ID, SELLER_ID, new BigDecimal("1"),
                "TON", T0);
        Orders orders = new Orders().put(ORDER_ID, undeployed);

        var undeployedResp = controller(orders).chainTx(
                new EscrowChainTxController.ChainTxRequest(initData(BUYER_ID), ORDER_ID, "FUND"));
        assertThat(undeployedResp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(undeployedResp.getBody().get("error"))).contains("尚未部署");

        Orders deployed = new Orders().put(ORDER_ID, deployedOrder());
        var badAction = controller(deployed).chainTx(
                new EscrowChainTxController.ChainTxRequest(initData(BUYER_ID), ORDER_ID, "LAUNCH"));
        assertThat(badAction.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("钱包绑定：buyer 绑买家列、seller 绑卖家列、陌生人 403")
    void walletBindsByRole() {
        Orders orders = new Orders().put(ORDER_ID, deployedOrder());
        String buyerWallet = validAddress((byte) 0x11);
        String sellerWallet = validAddress((byte) 0x22);

        var asBuyer = controller(orders).bindWallet(new EscrowChainTxController.WalletBindRequest(
                initData(BUYER_ID), ORDER_ID, buyerWallet));
        assertThat(asBuyer.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(asBuyer.getBody()).containsEntry("role", "BUYER");
        assertThat(orders.get(ORDER_ID).getBuyerTonAddress()).isEqualTo(buyerWallet);

        var asSeller = controller(orders).bindWallet(new EscrowChainTxController.WalletBindRequest(
                initData(SELLER_ID), ORDER_ID, sellerWallet));
        assertThat(asSeller.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(orders.get(ORDER_ID).getSellerTonAddress()).isEqualTo(sellerWallet);

        var asStranger = controller(orders).bindWallet(new EscrowChainTxController.WalletBindRequest(
                initData(STRANGER_ID), ORDER_ID, validAddress((byte) 0x33)));
        assertThat(asStranger.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("TON Connect manifest：按配置域名生成 url/iconUrl；未配置时回退请求 origin")
    void manifestReflectsConfiguredUrl() {
        Orders orders = new Orders();
        var withUrl = new EscrowChainTxController(
                new WebAppInitDataVerifier(BOT_TOKEN, Duration.ofHours(1), CLOCK),
                orders, orders, true, "https://escrow.example.com/", CLOCK);
        var resp = withUrl.tonConnectManifest(
                org.mockito.Mockito.mock(jakarta.servlet.http.HttpServletRequest.class));
        assertThat(resp.getBody())
                .containsEntry("url", "https://escrow.example.com/miniapp")
                .containsEntry("iconUrl", "https://escrow.example.com/icon-180.png");

        var fallback = new EscrowChainTxController(
                new WebAppInitDataVerifier(BOT_TOKEN, Duration.ofHours(1), CLOCK),
                orders, orders, true, "", CLOCK);
        jakarta.servlet.http.HttpServletRequest req =
                org.mockito.Mockito.mock(jakarta.servlet.http.HttpServletRequest.class);
        org.mockito.Mockito.when(req.getScheme()).thenReturn("http");
        org.mockito.Mockito.when(req.getServerName()).thenReturn("127.0.0.1");
        org.mockito.Mockito.when(req.getServerPort()).thenReturn(8080);
        assertThat(fallback.tonConnectManifest(req).getBody())
                .containsEntry("url", "http://127.0.0.1:8080/miniapp");
    }

    @Test
    @DisplayName("order-actions：角色 × 部署预检——buyer 见 FUND/CONFIRM/REFUND/DISPUTE、seller 见 DELIVER/REFUND/DISPUTE")
    void orderActionsReflectRoleAndDeployment() {
        Orders orders = new Orders().put(ORDER_ID, deployedOrder());

        var asBuyer = controller(orders).orderActions(
                new EscrowChainTxController.OrderActionsRequest(initData(BUYER_ID), ORDER_ID));
        assertThat(asBuyer.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(asBuyer.getBody()).containsEntry("ok", true)
                .containsEntry("role", "BUYER").containsEntry("deployed", true)
                .containsEntry("state", "OPEN");
        assertThat(actionsOf(asBuyer.getBody()))
                .as("买方全集——中间状态不硬收窄（链下状态是流程登记、非链上镜像），由合约最终裁决")
                .containsExactly("FUND", "CONFIRM", "REFUND", "DISPUTE");

        var asSeller = controller(orders).orderActions(
                new EscrowChainTxController.OrderActionsRequest(initData(SELLER_ID), ORDER_ID));
        assertThat(actionsOf(asSeller.getBody()))
                .containsExactly("DELIVER", "REFUND", "DISPUTE");
    }

    @Test
    @DisplayName("order-actions：未部署 → 空集 + deployed:false；终态 → 空集；陌生人 → 403")
    void orderActionsEmptyWhenNotActionable() {
        EscrowOrder undeployed = new EscrowOrder(BUYER_ID, SELLER_ID, new BigDecimal("1"), "TON", T0);
        Orders undeployedOrders = new Orders().put(ORDER_ID, undeployed);
        var undeployedResp = controller(undeployedOrders).orderActions(
                new EscrowChainTxController.OrderActionsRequest(initData(BUYER_ID), ORDER_ID));
        assertThat(undeployedResp.getBody()).containsEntry("deployed", false);
        assertThat(actionsOf(undeployedResp.getBody())).isEmpty();

        EscrowOrder finished = deployedOrder();
        finished.markConfirmed(T0);
        finished.markLocked(T0);
        finished.markDelivered(T0);
        finished.markReleased(T0);
        Orders doneOrders = new Orders().put(ORDER_ID, finished);
        var terminalResp = controller(doneOrders).orderActions(
                new EscrowChainTxController.OrderActionsRequest(initData(BUYER_ID), ORDER_ID));
        assertThat(terminalResp.getBody()).containsEntry("state", "RELEASED");
        assertThat(actionsOf(terminalResp.getBody())).as("终态无链上动作").isEmpty();

        var stranger = controller(undeployedOrders).orderActions(
                new EscrowChainTxController.OrderActionsRequest(initData(STRANGER_ID), ORDER_ID));
        assertThat(stranger.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    /** 响应体 actions 字段的便捷取值。 */
    @SuppressWarnings("unchecked")
    private static List<String> actionsOf(Map<String, Object> body) {
        return (List<String>) body.get("actions");
    }
}
