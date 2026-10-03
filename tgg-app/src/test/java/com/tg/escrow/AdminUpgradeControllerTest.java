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

import com.tg.escrow.chain.ChainGateway;
import com.tg.escrow.chain.ChainMessageSender;
import com.tg.escrow.chain.ChainSettings;
import com.tg.escrow.chain.UpgradeMessageCodec;
import com.tg.escrow.chain.UpgradeStatus;
import com.tg.escrow.chain.UpgradeStatusQuery;
import com.tg.escrow.escrow.EscrowMultiSigRule.Party;
import com.tg.escrow.escrow.EscrowUpgradeService;
import com.tg.escrow.escrow.PartySignatureVerifier;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.ton.ton4j.cell.Cell;
import org.ton.ton4j.cell.CellBuilder;

/**
 * {@link AdminUpgradeController} 直调单测（遗留修复 T6）——真实编排 + 真实 ChainGateway
 * （withUpgradeWriters 注入 recording 替身），非 mock：CTRL-1~7 与失败矩阵。
 *
 * <p>签名替身与 {@code EscrowUpgradeServiceTest} 同款：签名值 = 方名 UTF-8 字节才算过
 * （伪造签名即被剔除）。时钟钉死在 NOW——15 分钟签发时效窗口可控。
 */
class AdminUpgradeControllerTest {

    private static final Instant NOW = Instant.parse("2026-09-30T12:00:00Z");
    private static final String CONTRACT = "EQCxE6mUtQJKFnGfaROTKOt1lZbDiiX1kCixRv7Nw2Id_sDs";

    private final PartySignatureVerifier verifier =
            (party, payload, signature) -> party.name()
                    .equals(new String(signature, StandardCharsets.UTF_8));

    private record Sent(String contract, Cell body, long value) {
    }

    private final List<Sent> sent = new ArrayList<>();
    private final UpgradeStatus queryResult = new UpgradeStatus(BigInteger.TEN, 1_700_000_000L);

    private AdminUpgradeController controller(ChainGateway chain) {
        return new AdminUpgradeController(
                new EscrowUpgradeService(Clock.fixed(NOW, ZoneOffset.UTC)), verifier, chain);
    }

    /** 接线后的 gateway：升级三动作走 recording 发送替身（真实依赖链，仅网络出口被替）。 */
    private ChainGateway wiredGateway() {
        ChainMessageSender recording = (contract, body, value) -> sent.add(new Sent(contract, body, value));
        UpgradeStatusQuery query = contract -> queryResult;
        return ChainGateway.of(new ChainSettings(CONTRACT, true), (m, o) -> CONTRACT)
                .withUpgradeWriters(recording, query);
    }

    private static ChainGateway unwiredGateway() {
        return ChainGateway.of(new ChainSettings(CONTRACT, true), (m, o) -> CONTRACT);
    }

    private static Map<String, String> validSignatures() {
        Map<String, String> signatures = new HashMap<>();
        signatures.put("FEDERATION", hex(Party.FEDERATION.name().getBytes(StandardCharsets.UTF_8)));
        signatures.put("BUYER", hex(Party.BUYER.name().getBytes(StandardCharsets.UTF_8)));
        return signatures;
    }

    private static String hex(byte[] bytes) {
        return org.apache.commons.codec.binary.Hex.encodeHexString(bytes);
    }

    private static String newCodeBoc() {
        return Base64.getEncoder().encodeToString(
                CellBuilder.beginCell().storeUint(0xABCD, 16).endCell().toBoc());
    }

    @Test
    void proposePassesGuardAndExecutesOnChain() {
        ResponseEntity<Map<String, Object>> res = controller(wiredGateway()).propose(
                new AdminUpgradeController.UpgradeProposeRequest(
                        CONTRACT, newCodeBoc(), validSignatures(), NOW.getEpochSecond()));

        assertThat(res.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(res.getBody()).containsEntry("chainExecuted", true)
                .containsEntry("action", "PROPOSE")
                .containsEntry("ok", true);
        // 链上发出的确实是 ProposeUpgrade 消息体（record 替身捕获）
        assertThat(sent).hasSize(1);
        assertThat(org.ton.ton4j.cell.CellSlice.beginParse(sent.get(0).body()).loadUint(32))
                .isEqualTo(BigInteger.valueOf(UpgradeMessageCodec.OP_PROPOSE_UPGRADE));
    }

    @Test
    void applyPassesGuardAndExecutesOnChain() {
        ResponseEntity<Map<String, Object>> res = controller(wiredGateway()).apply(
                new AdminUpgradeController.UpgradeActionRequest(
                        CONTRACT, "abcd", validSignatures(), NOW.getEpochSecond()));

        assertThat(res.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(res.getBody()).containsEntry("chainExecuted", true)
                .containsEntry("action", "APPLY");
    }

    @Test
    void cancelPassesGuardAndExecutesOnChain() {
        ResponseEntity<Map<String, Object>> res = controller(wiredGateway()).cancel(
                new AdminUpgradeController.UpgradeActionRequest(
                        CONTRACT, "abcd", validSignatures(), NOW.getEpochSecond()));

        assertThat(res.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(res.getBody()).containsEntry("action", "CANCEL");
    }

    @Test
    void forgedSignatureIsRejectedWith400() {
        Map<String, String> signatures = new HashMap<>();
        signatures.put("FEDERATION", hex(Party.FEDERATION.name().getBytes(StandardCharsets.UTF_8)));
        signatures.put("BUYER", hex("FORGED".getBytes(StandardCharsets.UTF_8)));

        ResponseEntity<Map<String, Object>> res = controller(wiredGateway()).apply(
                new AdminUpgradeController.UpgradeActionRequest(
                        CONTRACT, "abcd", signatures, NOW.getEpochSecond()));

        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat(String.valueOf(res.getBody().get("error"))).contains("多签");
    }

    @Test
    void unwiredChainReportsHonestlyWithoutFaking() {
        ResponseEntity<Map<String, Object>> res = controller(unwiredGateway()).apply(
                new AdminUpgradeController.UpgradeActionRequest(
                        CONTRACT, "abcd", validSignatures(), NOW.getEpochSecond()));

        // 守卫通过但写链栈未接线：200 + chainExecuted=false + 如实 note（不假装已上链）
        assertThat(res.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(res.getBody()).containsEntry("chainExecuted", false);
        assertThat(String.valueOf(res.getBody().get("chainNote"))).contains("未接线");
    }

    @Test
    void corruptNewCodeBocIs400Not500() {
        ResponseEntity<Map<String, Object>> res = controller(wiredGateway()).propose(
                new AdminUpgradeController.UpgradeProposeRequest(
                        CONTRACT, "!!!not-base64!!!", validSignatures(), NOW.getEpochSecond()));
        assertThat(res.getStatusCode().value()).isEqualTo(400);

        // 合法 base64 但非法 boc——ton4j 抛裸 Error，必须兜成 400 而不是 500
        ResponseEntity<Map<String, Object>> res2 = controller(wiredGateway()).propose(
                new AdminUpgradeController.UpgradeProposeRequest(
                        CONTRACT, Base64.getEncoder().encodeToString(new byte[] {1, 2, 3}),
                        validSignatures(), NOW.getEpochSecond()));
        assertThat(res2.getStatusCode().value()).isEqualTo(400);
        assertThat(String.valueOf(res2.getBody().get("error"))).contains("boc");
    }

    @Test
    void statusIsPassedThrough() {
        ResponseEntity<Map<String, Object>> res = controller(wiredGateway()).status(CONTRACT);

        assertThat(res.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(res.getBody())
                .containsEntry("hasProposal", true)
                .containsEntry("proposedAt", 1_700_000_000L);
        assertThat(String.valueOf(res.getBody().get("codeHashHex")))
                .isEqualTo(String.format("%064x", BigInteger.TEN));
    }

    @Test
    void statusWithoutWriterIs502() {
        ResponseEntity<Map<String, Object>> res = controller(unwiredGateway()).status(CONTRACT);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
    }

    @Test
    void missingFieldsAre400() {
        AdminUpgradeController c = controller(wiredGateway());
        assertThat(c.propose(null).getStatusCode().value()).isEqualTo(400);
        assertThat(c.apply(null).getStatusCode().value()).isEqualTo(400);
        assertThat(c.propose(new AdminUpgradeController.UpgradeProposeRequest(
                " ", newCodeBoc(), validSignatures(), NOW.getEpochSecond()))
                .getStatusCode().value()).isEqualTo(400);
        assertThat(c.apply(new AdminUpgradeController.UpgradeActionRequest(
                CONTRACT, " ", validSignatures(), NOW.getEpochSecond()))
                .getStatusCode().value()).isEqualTo(400);
        assertThat(c.status(" ").getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void unknownPartyAndBadHexAre400() {
        Map<String, String> unknown = Map.of("HACKER", "00");
        ResponseEntity<Map<String, Object>> res = controller(wiredGateway()).apply(
                new AdminUpgradeController.UpgradeActionRequest(
                        CONTRACT, "abcd", unknown, NOW.getEpochSecond()));
        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat(String.valueOf(res.getBody().get("error"))).contains("未知参与方");

        Map<String, String> badHex = Map.of("BUYER", "zz");
        ResponseEntity<Map<String, Object>> res2 = controller(wiredGateway()).apply(
                new AdminUpgradeController.UpgradeActionRequest(
                        CONTRACT, "abcd", badHex, NOW.getEpochSecond()));
        assertThat(res2.getStatusCode().value()).isEqualTo(400);
        assertThat(String.valueOf(res2.getBody().get("error"))).contains("hex");
    }

    @Test
    void staleIssuedAtIsRejected() {
        long stale = NOW.getEpochSecond() - 16 * 60;
        ResponseEntity<Map<String, Object>> res = controller(wiredGateway()).apply(
                new AdminUpgradeController.UpgradeActionRequest(
                        CONTRACT, "abcd", validSignatures(), stale));
        assertThat(res.getStatusCode().value()).isEqualTo(400);
        assertThat(String.valueOf(res.getBody().get("error"))).contains("时效");
    }
}
