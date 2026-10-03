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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tg.escrow.chain.ChainGateway;
import com.tg.escrow.chain.ChainUnavailableException;
import com.tg.escrow.chain.UpgradeStatus;
import com.tg.escrow.escrow.EscrowUpgradeService;
import com.tg.escrow.escrow.PartySignatureVerifier;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.ton.ton4j.cell.CellBuilder;

/**
 * {@link AdminUpgradeController} 的 HTTP/JSON 验收切片（遗留修复 T7）——照
 * {@code AdminVerdictEndpointAcceptanceTest} 同款形态：@WebMvcTest + @Import(SecurityConfig)
 * + @TestPropertySource 注入凭据。本类专钉 <b>Jackson 反序列化</b>（record 字段名错位直调
 * 测试测不出）与 <b>鉴权面</b>（未配置/未带凭据 401 fail-closed）。
 */
@WebMvcTest(controllers = AdminUpgradeController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "tgg.admin.username=admin",
        "tgg.admin.password=secret",
})
class AdminUpgradeEndpointAcceptanceTest {

    private static final Instant NOW = Instant.parse("2026-09-30T12:00:00Z");
    private static final String CONTRACT = "EQCxE6mUtQJKFnGfaROTKOt1lZbDiiX1kCixRv7Nw2Id_sDs";

    /** 与直调单测同款 stub：签名值 = 方名 UTF-8 字节才算过。 */
    @TestConfiguration
    static class UpgradeWiring {
        @Bean
        EscrowUpgradeService escrowUpgradeService() {
            return new EscrowUpgradeService(Clock.fixed(NOW, ZoneOffset.UTC));
        }

        @Bean
        PartySignatureVerifier partySignatureVerifier() {
            return (party, payload, signature) -> party.name()
                    .equals(new String(signature, StandardCharsets.UTF_8));
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChainGateway chain;

    private static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder()
                .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    private static String signaturesJson() {
        // FEDERATION/BUYER 的签名 = 方名 UTF-8 字节的 hex（stub 验签器口径）
        return "{\"FEDERATION\":\"" + org.apache.commons.codec.binary.Hex
                .encodeHexString("FEDERATION".getBytes(StandardCharsets.UTF_8))
                + "\",\"BUYER\":\"" + org.apache.commons.codec.binary.Hex
                .encodeHexString("BUYER".getBytes(StandardCharsets.UTF_8)) + "\"}";
    }

    private static String proposeJson() {
        String boc = Base64.getEncoder().encodeToString(
                CellBuilder.beginCell().storeUint(0xABCD, 16).endCell().toBoc());
        return "{\"contractAddress\":\"" + CONTRACT + "\",\"newCodeBoc\":\"" + boc
                + "\",\"signatures\":" + signaturesJson()
                + ",\"issuedAt\":" + NOW.getEpochSecond() + "}";
    }

    @Test
    void upgradeEndpointsRequireAuthentication() throws Exception {
        mockMvc.perform(post("/admin/upgrade/propose")
                        .contentType(MediaType.APPLICATION_JSON).content(proposeJson()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/admin/upgrade/apply")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/admin/upgrade/status").param("contract", CONTRACT))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void proposeJsonRoundTripsThroughJackson() throws Exception {
        // record 字段名（contractAddress/newCodeBoc/signatures/issuedAt）被 Jackson 真实反序列化——
        // 字段名错位会在此红
        doNothing().when(chain).proposeUpgrade(anyString(), any());

        mockMvc.perform(post("/admin/upgrade/propose")
                        .header("Authorization", basic("admin", "secret"))
                        .contentType(MediaType.APPLICATION_JSON).content(proposeJson()))
                .andExpect(status().is2xxSuccessful())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.chainExecuted").value(true))
                .andExpect(jsonPath("$.action").value("PROPOSE"));
    }

    @Test
    void applyActionJsonRoundTrips() throws Exception {
        doNothing().when(chain).applyUpgrade(anyString());
        String body = "{\"contractAddress\":\"" + CONTRACT + "\",\"codeHashHex\":\"abcd\""
                + ",\"signatures\":" + signaturesJson()
                + ",\"issuedAt\":" + NOW.getEpochSecond() + "}";

        mockMvc.perform(post("/admin/upgrade/apply")
                        .header("Authorization", basic("admin", "secret"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is2xxSuccessful())
                .andExpect(jsonPath("$.action").value("APPLY"))
                .andExpect(jsonPath("$.chainExecuted").value(true));
    }

    @Test
    void unwiredChainIsReportedHonestlyOverHttp() throws Exception {
        doThrow(new ChainUnavailableException("写链栈未接线——升级/裁决的链上执行恒「未执行」"))
                .when(chain).applyUpgrade(anyString());
        String body = "{\"contractAddress\":\"" + CONTRACT + "\",\"codeHashHex\":\"abcd\""
                + ",\"signatures\":" + signaturesJson()
                + ",\"issuedAt\":" + NOW.getEpochSecond() + "}";

        mockMvc.perform(post("/admin/upgrade/apply")
                        .header("Authorization", basic("admin", "secret"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is2xxSuccessful())
                .andExpect(jsonPath("$.chainExecuted").value(false))
                .andExpect(jsonPath("$.chainNote").value(
                        org.hamcrest.Matchers.containsString("未接线")));
    }

    @Test
    void corruptBocIs400OverHttp() throws Exception {
        String body = "{\"contractAddress\":\"" + CONTRACT + "\",\"newCodeBoc\":\"!!!\""
                + ",\"signatures\":" + signaturesJson()
                + ",\"issuedAt\":" + NOW.getEpochSecond() + "}";
        mockMvc.perform(post("/admin/upgrade/propose")
                        .header("Authorization", basic("admin", "secret"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void statusJsonShapeIsPinned() throws Exception {
        org.mockito.Mockito.when(chain.upgradeStatus(anyString()))
                .thenReturn(new UpgradeStatus(BigInteger.TEN, 1_700_000_000L));

        mockMvc.perform(get("/admin/upgrade/status").param("contract", CONTRACT)
                        .header("Authorization", basic("admin", "secret")))
                .andExpect(status().is2xxSuccessful())
                .andExpect(jsonPath("$.hasProposal").value(true))
                .andExpect(jsonPath("$.proposedAt").value(1_700_000_000L));

        assertThat(true).isTrue();   // 断言主干在 MockMvc expectations
    }
}
