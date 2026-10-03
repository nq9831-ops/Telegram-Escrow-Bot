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

import com.tg.escrow.common.EscrowException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 升级裁决的链下多签编排（⑥）——「升级权必须走联邦多签」这条硬约束在链下的落点。
 *
 * <p>与 {@code EscrowVerdictService} 同一范式（A0-2）：守卫链顺序
 * <b>单据构造 → 签发时效 → 逐方验签（fail-closed）→ 多签规则</b>。规则复用
 * {@link EscrowMultiSigRule}（≥2 方且必含联邦；买方+卖方组合无效）——升级比资金裁决
 * 更危险（换 code = 最高权力），规则绝不放宽。
 *
 * <p>链上侧的另一半防御是<b>合约内时间锁</b>（72h 冷静期）：即使本层被绕过、联邦 key
 * 单点被破，链上依然有三天窗口可察觉并 Cancel——两层缺一不可。
 *
 * <p><b>时钟一律注入</b>（项目惯例，同 {@code JpaModerationAdapters}）：签发时效窗口
 * 必须可在测试里钉住，不读系统时钟。
 */
public final class EscrowUpgradeService {

    /** 签发时效旧限（与 EscrowVerdictService 同口径）。 */
    private static final Duration MAX_AGE = Duration.ofMinutes(15);
    /** 签发时刻的前向容忍（与 EscrowVerdictService 同口径；时钟偏差不误杀真签发）。 */
    private static final Duration FORWARD_TOLERANCE = Duration.ofMinutes(5);

    private final Clock clock;

    public EscrowUpgradeService(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "时钟未提供");
    }

    /**
     * 验证一份升级裁决是否可执行——守卫链全过才返回验签通过的参与方，任一环不过即抛（fail-closed）。
     *
     * <p>验证通过只代表「链下多签成立」；链上执行（Propose/Apply/Cancel）由调用方经
     * 写链入口另行发出，合约内时间锁再守一道。
     *
     * @param approval   升级裁决单（其 {@link UpgradeApproval#canonicalBytes()} 即签名载荷）
     * @param signatures 参与方到签名值的映射；空或 {@code null} 表示无人签名
     * @param verifier   单方验签器
     * @return 验签通过的参与方（不可变集合）
     * @throws EscrowException 守卫链任一环不过（缺件/时效失窗/多签规则不满足）
     */
    public Set<EscrowMultiSigRule.Party> verifiedForExecution(UpgradeApproval approval,
                                                              Map<EscrowMultiSigRule.Party, byte[]> signatures,
                                                              PartySignatureVerifier verifier) {
        if (approval == null) {
            throw new EscrowException("升级守卫缺少裁决单");
        }
        if (verifier == null) {
            throw new EscrowException("升级守卫缺少验签器");
        }
        Instant now = clock.instant();
        requireFresh(approval, now);
        Set<EscrowMultiSigRule.Party> verified = verifiedParties(approval, signatures, verifier);
        if (!EscrowMultiSigRule.satisfies(verified)) {
            throw new EscrowException("升级多签规则不满足（须 ≥" + EscrowMultiSigRule.REQUIRED_SIGNERS
                    + " 方且必含联邦），验签通过方：" + verified);
        }
        return verified;
    }

    /** 签发时效守卫：裁决单过旧或签发时刻超前过多都拒（fail-closed）。 */
    private void requireFresh(UpgradeApproval approval, Instant now) {
        Instant issuedAt = Instant.ofEpochSecond(approval.issuedAt());
        if (issuedAt.isBefore(now.minus(MAX_AGE))) {
            throw new EscrowException("升级裁决已过签发时效（超过 " + MAX_AGE.toMinutes() + " 分钟）");
        }
        if (issuedAt.isAfter(now.plus(FORWARD_TOLERANCE))) {
            throw new EscrowException("升级裁决签发时刻超前过多（超过前向容忍 "
                    + FORWARD_TOLERANCE.toMinutes() + " 分钟）");
        }
    }

    /**
     * 逐方验签（fail-closed，与 EscrowVerdictGuard 同范式）：验不过的签名<b>剔除而非记入</b>；
     * 验签器抛异常按验签失败降级（拒绝），绝不带病放行。
     */
    private static Set<EscrowMultiSigRule.Party> verifiedParties(UpgradeApproval approval,
                                                                 Map<EscrowMultiSigRule.Party, byte[]> signatures,
                                                                 PartySignatureVerifier verifier) {
        if (signatures == null || signatures.isEmpty()) {
            return Collections.emptySet();
        }
        byte[] payload = approval.canonicalBytes();
        Set<EscrowMultiSigRule.Party> verified = new LinkedHashSet<>();
        for (Map.Entry<EscrowMultiSigRule.Party, byte[]> entry : signatures.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            try {
                if (verifier.verify(entry.getKey(), payload, entry.getValue())) {
                    verified.add(entry.getKey());
                }
            } catch (RuntimeException ex) {
                // fail-closed：验签器异常 = 验签失败，剔除该签名
            }
        }
        return Collections.unmodifiableSet(verified);
    }
}
