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
import com.tg.escrow.chain.ChainUnavailableException;
import com.tg.escrow.escrow.EscrowOrder;
import com.tg.escrow.escrow.EscrowOrderLookupPort;
import com.tg.escrow.escrow.EscrowOrderStore;
import com.tg.escrow.escrow.EscrowVerdict;
import com.tg.escrow.escrow.EscrowVerdictService;
import com.tg.escrow.escrow.EscrowMultiSigRule.Party;
import com.tg.escrow.escrow.PartySignatureVerifier;
import com.tg.escrow.federation.FederationKeyPair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /admin/verdict} 的<b>用户级行为验收</b>（HTTP/JSON 层）——
 * 运维脚本发真实 JSON 请求，验的是"用户做什么 → 看到什么"，不是 controller 方法直调。
 *
 * <p>与 {@link AdminVerdictControllerTest}（直调 {@code execute}）互补：本测试钉住
 * <b>Jackson 对裁决请求体 {@code issuedAt} 字段的反序列化</b>——record 新增字段若与 JSON
 * 键名错位，直调测试永远发现不了，而运维请求会拿到 400/空字段。
 *
 * <p>验签走真实 {@link FederationKeyPair}（Ed25519）+ 买卖方字节比对（钱包验签在 tgg-chain），
 * 签发时刻刻意与服务端时钟错开——这正是被修复缺陷的用户级形态。
 */
@WebMvcTest(controllers = AdminVerdictController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "tgg.admin.username=ops",
        "tgg.admin.password=secret-for-test"
})
class AdminVerdictEndpointAcceptanceTest {

    private static final Instant NOW = Instant.parse("2026-09-29T12:00:00Z");
    private static final long ORDER_ID = 7L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChainGateway chainGateway;

    @BeforeEach
    void chainNotDeployed() throws ChainUnavailableException {
        // 忠实生产现状：合约未上链时 resolveDispute 恒「未执行」——回执不得假装已出款
        doThrow(new ChainUnavailableException("合约未部署，未发生真实出款"))
                .when(chainGateway).resolveDispute(any(), anyBoolean());
    }

    /** 真实裁决编排（守卫链 + 时效窗口），只把链上执行钉为"未执行"。 */
    @TestConfiguration
    static class VerdictWiring {
        @Bean
        EscrowVerdictService verdictService() {
            return new EscrowVerdictService(new InMemoryStore(), Clock.fixed(NOW, ZoneOffset.UTC),
                    realVerifier());
        }

        @Bean
        com.tg.escrow.escrow.FeePolicy feePolicy() {
            return com.tg.escrow.escrow.FeePolicy.zero();
        }

        /** ET-61：裁决入口新增的惩罚编排依赖（本切片只验 HTTP 层，用内存替身）。 */
        @Bean
        com.tg.escrow.escrow.PenaltyService penaltyService() {
            return StubPenalty.service(Clock.fixed(NOW, ZoneOffset.UTC));
        }

        @Bean
        EscrowOrderLookupPort lookupPort() {
            // 端口已是两方法（byId + recentFor），不能再写成 lambda——用匿名类显式实现两者
            return new EscrowOrderLookupPort() {
                @Override
                public Optional<EscrowOrder> byId(long id) {
                    return Optional.ofNullable(id == ORDER_ID ? disputedOrder() : null);
                }

                @Override
                public java.util.List<EscrowOrder> recentFor(long userId, int limit) {
                    return java.util.List.of();   // 本切片只走按单号查询
                }
            };
        }

        static PartySignatureVerifier realVerifier() {
            byte[] seed = new byte[FederationKeyPair.SEED_LENGTH];
            for (int i = 0; i < seed.length; i++) {
                seed[i] = (byte) (i + 1);
            }
            FederationKeyPair keys = FederationKeyPair.fromSeed(seed);
            Instant issuedAt = NOW.minusSeconds(60);
            byte[] signedPayload = new EscrowVerdict(ORDER_ID, EscrowVerdict.Outcome.RELEASE,
                    "联邦裁决：支持卖方", issuedAt).canonicalBytes();
            REAL_KEYS = keys;
            REAL_PAYLOAD = signedPayload;
            return (party, payload, signature) -> {
                if (party == Party.FEDERATION) {
                    return keys.verify(payload, signature);
                }
                return party == Party.BUYER
                        && Arrays.equals(payload, signedPayload)
                        && Arrays.equals(signature, new byte[]{42});
            };
        }
    }

    /** 联邦密钥与签发载荷（供用例拼 JSON——签名方持有的就是这份载荷的签名）。 */
    private static FederationKeyPair REAL_KEYS;
    private static byte[] REAL_PAYLOAD;

    private static EscrowOrder disputedOrder() {
        EscrowOrder order = new EscrowOrder(1001L, 2002L, new BigDecimal("100"), "USDT", NOW);
        order.assignId(ORDER_ID);
        order.markLocked(NOW);
        order.markDisputed("货不对板", NOW);
        return order;
    }

    private static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder()
                .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /** 一次用户级动作：发真实 JSON 裁决请求（签发时刻 ≠ 服务端时钟）。 */
    private String verdictJson(String issuedAtEpochSecond) {
        byte[] federationSig = REAL_KEYS.sign(REAL_PAYLOAD);
        return "{\"orderId\":" + ORDER_ID
                + ",\"outcome\":\"RELEASE\""
                + ",\"reason\":\"联邦裁决：支持卖方\""
                + ",\"issuedAt\":" + issuedAtEpochSecond
                + ",\"signatures\":{\"FEDERATION\":\"" + hex(federationSig) + "\",\"BUYER\":\"2a\"}}";
    }

    @Test
    @DisplayName("用户级验收①：POST /admin/verdict 真实 JSON（含 issuedAt，签发≠接收时刻）→ 2xx 且 ok=true")
    void verdictViaHttpJsonIsAccepted() throws Exception {
        Instant issuedAt = NOW.minusSeconds(60);

        mockMvc.perform(post("/admin/verdict")
                        .header("Authorization", basic("ops", "secret-for-test"))
                        .contentType("application/json")
                        .content(verdictJson(String.valueOf(issuedAt.getEpochSecond()))))
                .andExpect(status().is2xxSuccessful())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.state").value("RELEASED"))
                .andExpect(jsonPath("$.chainExecuted").value(false));
    }

    @Test
    @DisplayName("用户级验收②：issuedAt 超出有效窗口 → 400 且 error 含「签发时刻」")
    void staleIssuedAtViaHttpJsonIsRejected() throws Exception {
        Instant stale = NOW.minusSeconds(30 * 60);

        mockMvc.perform(post("/admin/verdict")
                        .header("Authorization", basic("ops", "secret-for-test"))
                        .contentType("application/json")
                        .content(verdictJson(String.valueOf(stale.getEpochSecond()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("签发时刻")));
    }

    /** 内存订单存储（验收用——不落库）。 */
    private static final class InMemoryStore implements EscrowOrderStore {
        @Override
        public EscrowOrder save(EscrowOrder order) {
            return order;
        }
    }
}
