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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tg.escrow.common.EscrowException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link EscrowUpgradeService} 守卫链测试（⑥ 链下多签）——矩阵照 EscrowVerdictService 同款：
 * 合规多签放行、伪造签名剔除、买卖双方组合无效、时效失窗拒、验签异常 fail-closed、载荷防挪用。
 */
class EscrowUpgradeServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-30T12:00:00Z");
    private static final String CONTRACT = "EQD__________________________________________0";

    private final EscrowUpgradeService service = new EscrowUpgradeService(
            Clock.fixed(NOW, ZoneOffset.UTC));

    /** 确定性假验签器：签名值 = 方名 UTF-8 字节才算过（伪造签名即被剔除）。 */
    private final PartySignatureVerifier verifier =
            (party, payload, signature) -> party.name()
                    .equals(new String(signature, StandardCharsets.UTF_8));

    private static UpgradeApproval approval(long issuedAt) {
        return new UpgradeApproval(CONTRACT, UpgradeApproval.Action.PROPOSE, "ABCD", issuedAt);
    }

    private static byte[] sigFor(EscrowMultiSigRule.Party party) {
        return party.name().getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void compliantMultiSigWithFederationIsAccepted() {
        Map<EscrowMultiSigRule.Party, byte[]> signatures = new EnumMap<>(EscrowMultiSigRule.Party.class);
        signatures.put(EscrowMultiSigRule.Party.FEDERATION, sigFor(EscrowMultiSigRule.Party.FEDERATION));
        signatures.put(EscrowMultiSigRule.Party.BUYER, sigFor(EscrowMultiSigRule.Party.BUYER));

        Set<EscrowMultiSigRule.Party> verified =
                service.verifiedForExecution(approval(NOW.getEpochSecond()), signatures, verifier);

        assertThat(verified).containsExactlyInAnyOrder(
                EscrowMultiSigRule.Party.FEDERATION, EscrowMultiSigRule.Party.BUYER);
    }

    @Test
    void buyerAndSellerWithoutFederationIsRejected() {
        Map<EscrowMultiSigRule.Party, byte[]> signatures = new EnumMap<>(EscrowMultiSigRule.Party.class);
        signatures.put(EscrowMultiSigRule.Party.BUYER, sigFor(EscrowMultiSigRule.Party.BUYER));
        signatures.put(EscrowMultiSigRule.Party.SELLER, sigFor(EscrowMultiSigRule.Party.SELLER));

        assertThatThrownBy(() -> service.verifiedForExecution(
                approval(NOW.getEpochSecond()), signatures, verifier))
                .isInstanceOf(EscrowException.class)
                .hasMessageContaining("多签规则不满足");
    }

    @Test
    void forgedSignatureIsDroppedAndLeavesRuleUnsatisfied() {
        Map<EscrowMultiSigRule.Party, byte[]> signatures = new EnumMap<>(EscrowMultiSigRule.Party.class);
        signatures.put(EscrowMultiSigRule.Party.FEDERATION, sigFor(EscrowMultiSigRule.Party.FEDERATION));
        signatures.put(EscrowMultiSigRule.Party.BUYER, "FORGED".getBytes(StandardCharsets.UTF_8));

        // 伪造的买方签名被剔除 → 只剩联邦一方 → 多签规则不满足
        assertThatThrownBy(() -> service.verifiedForExecution(
                approval(NOW.getEpochSecond()), signatures, verifier))
                .isInstanceOf(EscrowException.class)
                .hasMessageContaining("多签规则不满足");
    }

    @Test
    void noSignaturesIsRejected() {
        assertThatThrownBy(() -> service.verifiedForExecution(
                approval(NOW.getEpochSecond()), Map.of(), verifier))
                .isInstanceOf(EscrowException.class);
    }

    @Test
    void staleIssuedAtIsRejected() {
        Map<EscrowMultiSigRule.Party, byte[]> signatures = new EnumMap<>(EscrowMultiSigRule.Party.class);
        signatures.put(EscrowMultiSigRule.Party.FEDERATION, sigFor(EscrowMultiSigRule.Party.FEDERATION));
        signatures.put(EscrowMultiSigRule.Party.BUYER, sigFor(EscrowMultiSigRule.Party.BUYER));
        long stale = NOW.getEpochSecond() - 16 * 60;   // 超过 15 分钟旧限

        assertThatThrownBy(() -> service.verifiedForExecution(approval(stale), signatures, verifier))
                .isInstanceOf(EscrowException.class)
                .hasMessageContaining("签发时效");
    }

    @Test
    void farFutureIssuedAtIsRejected() {
        Map<EscrowMultiSigRule.Party, byte[]> signatures = new EnumMap<>(EscrowMultiSigRule.Party.class);
        signatures.put(EscrowMultiSigRule.Party.FEDERATION, sigFor(EscrowMultiSigRule.Party.FEDERATION));
        signatures.put(EscrowMultiSigRule.Party.BUYER, sigFor(EscrowMultiSigRule.Party.BUYER));
        long future = NOW.getEpochSecond() + 6 * 60;   // 超过 5 分钟前向容忍

        assertThatThrownBy(() -> service.verifiedForExecution(approval(future), signatures, verifier))
                .isInstanceOf(EscrowException.class)
                .hasMessageContaining("超前");
    }

    @Test
    void verifierExceptionIsFailClosed() {
        Map<EscrowMultiSigRule.Party, byte[]> signatures = new EnumMap<>(EscrowMultiSigRule.Party.class);
        signatures.put(EscrowMultiSigRule.Party.FEDERATION, sigFor(EscrowMultiSigRule.Party.FEDERATION));
        signatures.put(EscrowMultiSigRule.Party.BUYER, sigFor(EscrowMultiSigRule.Party.BUYER));
        PartySignatureVerifier exploding = (party, payload, signature) -> {
            throw new IllegalStateException("验签器故障");
        };

        // 两方验签都异常 → 全部剔除 → 规则不满足（绝不带病放行）
        assertThatThrownBy(() -> service.verifiedForExecution(
                approval(NOW.getEpochSecond()), signatures, exploding))
                .isInstanceOf(EscrowException.class);
    }

    @Test
    void missingPartsAreRejected() {
        assertThatThrownBy(() -> service.verifiedForExecution(null, Map.of(), verifier))
                .isInstanceOf(EscrowException.class);
        assertThatThrownBy(() -> service.verifiedForExecution(
                approval(NOW.getEpochSecond()), Map.of(), null))
                .isInstanceOf(EscrowException.class);
    }

    @Test
    void canonicalBytesBindAllFourDimensions() {
        byte[] base = approval(100).canonicalBytes();

        // 挪用防线：换任一维度，规范字节必须不同——签名不可跨单复用
        assertThat(new UpgradeApproval(CONTRACT, UpgradeApproval.Action.APPLY, "ABCD", 100)
                .canonicalBytes()).isNotEqualTo(base);
        assertThat(new UpgradeApproval(CONTRACT, UpgradeApproval.Action.PROPOSE, "ABCE", 100)
                .canonicalBytes()).isNotEqualTo(base);
        assertThat(new UpgradeApproval("EQD__________________________________________1",
                UpgradeApproval.Action.PROPOSE, "ABCD", 100).canonicalBytes()).isNotEqualTo(base);
        assertThat(approval(101).canonicalBytes()).isNotEqualTo(base);

        // 大小写归一：codeHash 大小写差异不得改变规范字节（防验签假失败）
        assertThat(new UpgradeApproval(CONTRACT, UpgradeApproval.Action.PROPOSE, "abcd", 100)
                .canonicalBytes()).isEqualTo(base);
    }
}
