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
import com.tg.escrow.chain.ChainMessageSender;
import com.tg.escrow.chain.ChainSettings;
import com.tg.escrow.chain.ResolveMessageCodec;
import com.tg.escrow.chain.UpgradeStatus;
import com.tg.escrow.escrow.EscrowOrder;
import com.tg.escrow.escrow.EscrowOrderLookupPort;
import com.tg.escrow.escrow.EscrowOrderStore;
import com.tg.escrow.escrow.EscrowVerdict;
import com.tg.escrow.escrow.EscrowVerdictService;
import com.tg.escrow.escrow.EscrowMultiSigRule.Party;
import com.tg.escrow.escrow.PartySignatureVerifier;
import com.tg.escrow.federation.FederationKeyPair;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 裁决入口（S5 Wave 3）：admin API 把裁决单+签名集送进编排层，并<b>如实明示链上未执行</b>。
 *
 * <p>与 {@code AdminChainControllerTest} 同风格：直调控制器方法、真实编排层 + 真实
 * {@link ChainGateway}（未配置形态——S5 合约未上链，{@code resolveDispute} 恒「未执行」）。
 * 守卫语义已在 {@code EscrowVerdictServiceTest} 穷举，这里钉<b>接线行为</b>与回执诚实性。
 */
class AdminVerdictControllerTest {

    private static final long ORDER_ID = 42L;
    private static final Instant NOW = Instant.parse("2026-09-29T12:00:00Z");

    private static final class StubVerifier implements PartySignatureVerifier {
        private final Map<Party, Boolean> answers = new EnumMap<>(Party.class);

        StubVerifier pass(Party party) {
            answers.put(party, true);
            return this;
        }

        @Override
        public boolean verify(Party party, byte[] payload, byte[] signature) {
            return answers.getOrDefault(party, false);
        }
    }

    private static final class InMemoryStore implements EscrowOrderStore {
        @Override
        public EscrowOrder save(EscrowOrder order) {
            return order;
        }
    }

    private static EscrowOrder disputedOrder() {
        EscrowOrder order = new EscrowOrder(1001L, 2002L, new BigDecimal("100"), "USDT", NOW);
        order.assignId(ORDER_ID);
        order.markLocked(NOW);
        order.markDisputed("货不对板", NOW);
        return order;
    }

    private static EscrowOrderLookupPort lookupOf(EscrowOrder order) {
        // 端口已是两方法（byId + recentFor），不能再写成 lambda——用匿名类显式实现两者
        return new EscrowOrderLookupPort() {
            @Override
            public Optional<EscrowOrder> byId(long id) {
                return Optional.ofNullable(order != null && order.getId() == id ? order : null);
            }

            @Override
            public java.util.List<EscrowOrder> recentFor(long userId, int limit) {
                return java.util.List.of();   // 本类只走按单号查询；列表场景不在其范围
            }
        };
    }

    private static AdminVerdictController controller(EscrowOrderLookupPort lookup,
                                                     PartySignatureVerifier verifier) {
        EscrowVerdictService svc = new EscrowVerdictService(
                new InMemoryStore(), Clock.fixed(NOW, ZoneOffset.UTC), verifier);
        return new AdminVerdictController(svc, lookup, ChainGateway.fromOptional(null, true),
                com.tg.escrow.escrow.FeePolicy.zero(),
                StubPenalty.service(Clock.fixed(NOW, ZoneOffset.UTC)));
    }

    /** 合法 friendly 地址（真 CRC——ChainSettings 校验会验形态）。 */
    private static String validChainAddr(byte fill) {
        byte[] hash = new byte[32];
        Arrays.fill(hash, fill);
        return org.ton.ton4j.address.Address
                .of(org.ton.ton4j.address.Address.BOUNCEABLE_TAG, 0, hash)
                .toString(true, true, true);
    }

    /** 写链接线版控制器（S5：验证 Resolve 已真尝试出链）。 */
    private static AdminVerdictController controllerWithChain(EscrowOrderLookupPort lookup,
                                                              PartySignatureVerifier verifier,
                                                              ChainMessageSender sender) {
        EscrowVerdictService svc = new EscrowVerdictService(
                new InMemoryStore(), Clock.fixed(NOW, ZoneOffset.UTC), verifier);
        ChainGateway wired = ChainGateway.of(
                        new ChainSettings(validChainAddr((byte) 0x77), true),
                        (master, owner) -> validChainAddr((byte) 0x77))
                .withUpgradeWriters(sender, contract -> new UpgradeStatus(BigInteger.ZERO, 0L));
        return new AdminVerdictController(svc, lookup, wired,
                com.tg.escrow.escrow.FeePolicy.zero(),
                StubPenalty.service(Clock.fixed(NOW, ZoneOffset.UTC)));
    }

    @Test
    @DisplayName("链上执行（S5）：订单已绑定合约地址 → Resolve 真发往该地址（chainExecuted=true）")
    void verdictSendsResolveToBoundContract() {
        String address = validChainAddr((byte) 0x99);
        EscrowOrder order = disputedOrder();
        order.attachChainContractAddress(address, NOW);
        List<Object[]> sent = new ArrayList<>();
        AdminVerdictController c = controllerWithChain(lookupOf(order),
                new StubVerifier().pass(Party.FEDERATION).pass(Party.BUYER),
                (contract, body, value) -> sent.add(new Object[] {contract, body, value}));

        ResponseEntity<Map<String, Object>> resp = c.execute(
                new AdminVerdictController.VerdictRequest(ORDER_ID, "RELEASE", "联邦裁决：支持卖方",
                        NOW.getEpochSecond(), Map.of("FEDERATION", "01", "BUYER", "02")));

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        Map<String, Object> body = resp.getBody();
        assertThat(body).containsEntry("chainExecuted", true);
        assertThat(sent).as("Resolve 发往订单绑定的合约地址").hasSize(1);
        assertThat(sent.get(0)[0]).isEqualTo(address);
        assertThat(((org.ton.ton4j.cell.Cell) sent.get(0)[1]).hash())
                .isEqualTo(ResolveMessageCodec.resolve(true).hash());
    }

    @Test
    @DisplayName("成功路径：裁决落状态且回执明示链上未执行（不假装已出款）")
    void verdictExecutesAndReportsChainNotDeployed() {
        AdminVerdictController c = controller(lookupOf(disputedOrder()),
                new StubVerifier().pass(Party.FEDERATION).pass(Party.BUYER));

        ResponseEntity<Map<String, Object>> resp = c.execute(
                new AdminVerdictController.VerdictRequest(ORDER_ID, "RELEASE", "联邦裁决：支持卖方",
                        NOW.getEpochSecond(), Map.of("FEDERATION", "01", "BUYER", "02")));

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        Map<String, Object> body = resp.getBody();
        assertThat(body).containsEntry("ok", true).containsEntry("state", "RELEASED");
        assertThat(body).containsEntry("chainExecuted", false);
        assertThat(String.valueOf(body.get("chainNote"))).contains("未");
    }

    @Test
    @DisplayName("拒绝：签名不足 → 400 且 error 含「多签」")
    void insufficientSignaturesRejected() {
        AdminVerdictController c = controller(lookupOf(disputedOrder()),
                new StubVerifier().pass(Party.FEDERATION));

        ResponseEntity<Map<String, Object>> resp = c.execute(
                new AdminVerdictController.VerdictRequest(ORDER_ID, "RELEASE", "理由",
                        NOW.getEpochSecond(), Map.of("FEDERATION", "01")));

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(String.valueOf(resp.getBody().get("error"))).contains("多签");
    }

    @Test
    @DisplayName("拒绝：非法 outcome / 未知参与方 → 400（请求错，不是服务错）")
    void invalidOutcomeOrPartyRejected() {
        AdminVerdictController c = controller(lookupOf(disputedOrder()),
                new StubVerifier().pass(Party.FEDERATION).pass(Party.BUYER));

        ResponseEntity<Map<String, Object>> badOutcome = c.execute(
                new AdminVerdictController.VerdictRequest(ORDER_ID, "PAYOUT", "理由",
                        NOW.getEpochSecond(), Map.of("FEDERATION", "01", "BUYER", "02")));
        assertThat(badOutcome.getStatusCode().value()).isEqualTo(400);

        ResponseEntity<Map<String, Object>> badParty = c.execute(
                new AdminVerdictController.VerdictRequest(ORDER_ID, "RELEASE", "理由",
                        NOW.getEpochSecond(), Map.of("STRANGER", "01", "BUYER", "02")));
        assertThat(badParty.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("拒绝：订单不存在 → 404")
    void orderNotFoundRejected() {
        AdminVerdictController c = controller(lookupOf(null),
                new StubVerifier().pass(Party.FEDERATION).pass(Party.BUYER));

        ResponseEntity<Map<String, Object>> resp = c.execute(
                new AdminVerdictController.VerdictRequest(ORDER_ID, "RELEASE", "理由",
                        NOW.getEpochSecond(), Map.of("FEDERATION", "01", "BUYER", "02")));

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("真实签名链路：签发时刻≠服务端接收时刻仍须验过（issuedAt 随请求携带）")
    void verdictSignedAtIssuedTimeIsAcceptedAcrossClockSkew() {
        byte[] seed = new byte[FederationKeyPair.SEED_LENGTH];
        for (int i = 0; i < seed.length; i++) {
            seed[i] = (byte) (i + 1);
        }
        FederationKeyPair keys = FederationKeyPair.fromSeed(seed);
        // 签发方（联邦成员）在 issuedAt 时刻对裁决单签名——与服务端接收时刻（NOW）不同
        Instant issuedAt = NOW.minusSeconds(60);
        byte[] signedPayload = new EscrowVerdict(ORDER_ID, EscrowVerdict.Outcome.RELEASE,
                "联邦裁决：支持卖方", issuedAt).canonicalBytes();
        byte[] federationSig = keys.sign(signedPayload);
        // 买卖方签名由钱包产生（ton_proof），此处以字节比对替身模拟「签的就是那份载荷」
        PartySignatureVerifier verifier = (party, payload, signature) -> {
            if (party == Party.FEDERATION) {
                return keys.verify(payload, signature);
            }
            return party == Party.BUYER
                    && Arrays.equals(payload, signedPayload)
                    && Arrays.equals(signature, new byte[]{42});
        };
        AdminVerdictController c = controller(lookupOf(disputedOrder()), verifier);

        ResponseEntity<Map<String, Object>> resp = c.execute(
                new AdminVerdictController.VerdictRequest(ORDER_ID, "RELEASE", "联邦裁决：支持卖方",
                        issuedAt.getEpochSecond(),
                        Map.of("FEDERATION", hex(federationSig), "BUYER", "2a")));

        assertThat(resp.getStatusCode().is2xxSuccessful())
                .as("issuedAt 随请求携带后，服务端不得以接收时刻重建裁决单（否则验签永败）")
                .isTrue();
        assertThat(resp.getBody()).containsEntry("ok", true).containsEntry("state", "RELEASED");
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    @Test
    @DisplayName("验收：裁决回执含仲裁费/联邦管理费字段（三费分离：联邦费从仲裁费分成，值 0 也显式给）")
    void verdictReceiptReportsArbitrationFees() {
        // 仲裁 10%、联邦分仲裁费 30%——100 金额 → 仲裁费 10、联邦费 3（不是按平台费算）
        AdminVerdictController c = new AdminVerdictController(
                new EscrowVerdictService(new InMemoryStore(), Clock.fixed(NOW, ZoneOffset.UTC),
                        new StubVerifier().pass(Party.FEDERATION).pass(Party.BUYER)),
                lookupOf(disputedOrder()), ChainGateway.fromOptional(null, true),
                com.tg.escrow.escrow.FeePolicy.of("0.02", "0.10", "0.30"),
                StubPenalty.service(Clock.fixed(NOW, ZoneOffset.UTC)));

        ResponseEntity<Map<String, Object>> resp = c.execute(
                new AdminVerdictController.VerdictRequest(ORDER_ID, "RELEASE", "理由",
                        NOW.getEpochSecond(), Map.of("FEDERATION", "01", "BUYER", "02")));

        assertThat(resp.getBody())
                .containsEntry("arbitrationFee", "10.000000")
                .containsEntry("federationFee", "3.000000")
                .containsEntry("netAmount", "90.000000");
    }
}
