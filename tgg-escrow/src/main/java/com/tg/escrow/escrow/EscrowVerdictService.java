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
import com.tg.escrow.common.TggException;
import com.tg.escrow.escrow.EscrowMultiSigRule.Party;
import com.tg.escrow.escrow.EscrowVerdict.Outcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * 裁决编排（S5 · 方案 A：链下多签裁决 + 链上联邦单方执行）——把链下裁决件接进订单生命周期。
 *
 * <h2>守卫链（全部 fail-closed，顺序即短路顺序）</h2>
 * <ol>
 *   <li>订单须处于 {@code DISPUTED}（{@link DisputeFlow#requireCanVerdict}，规则单一定义处）；</li>
 *   <li>裁决单须指向本单（换一份裁决旧签名必失效，但换一张单指向别处仍要拦）；</li>
 *   <li>签名须过 {@link EscrowVerdictGuard}（验签 fail-closed）+ {@link EscrowMultiSigRule}
 *       （≥2 方且必含联邦——买卖双方组合无效，C1 已定）。</li>
 * </ol>
 *
 * <h2>签名集的留痕（刻意的取舍）</h2>
 * <p>{@code escrow_orders} 无承载签名集的列、且「不新增表」是非目标——签名集与验签通过方
 * <b>不入库存档</b>，留痕在本类的执行日志（哪几方验签通过）与调用方回执。要审计级存档
 * 需部署者拍板新增历史表（撞非目标，已记入决策清单）。
 *
 * <h2>与链上的关系</h2>
 * <p>本类只做<b>状态登记</b>（与 {@link EscrowTradeService} 同一纪律：只改状态、不动钱）；
 * 真实出款属 S5 合约的 {@code Resolve}（由联邦单方上链执行），经 ChainGateway 接线（Wave 3）。
 */
public final class EscrowVerdictService {

    private static final Logger log = LoggerFactory.getLogger(EscrowVerdictService.class);

    /**
     * 裁决单有效窗口：签发时刻距当前超过此时长即视为陈旧（重放/积压签名防线）。
     * 多签裁决是管理操作，窗口按「一次裁决流程应当完成的时长」取 15 分钟。
     */
    private static final Duration MAX_ISSUED_AGE = Duration.ofMinutes(15);

    /** 时钟前向容忍：签发方时钟快于此值即拒（防时钟错乱把未来裁决当有效）。 */
    private static final Duration FUTURE_TOLERANCE = Duration.ofMinutes(5);

    private final EscrowOrderStore orderStore;
    private final Clock clock;
    private final PartySignatureVerifier verifier;
    /**
     * 裁决前置门（可空=不设）——{@code null} 时行为与既有完全一致，不改变默认部署契约。
     * 由配置驱动（如「争议双方须完成陈述」），故做成可空注入。
     */
    private final VerdictPrecondition precondition;

    public EscrowVerdictService(EscrowOrderStore orderStore, Clock clock, PartySignatureVerifier verifier) {
        this(orderStore, clock, verifier, null);
    }

    /**
     * 带裁决前置门的构造：{@code precondition} 非空时，{@link #execute} 在状态守卫之后、
     * 验签与落状态之前先过它——不满足即拒，绝不静默放行。
     */
    public EscrowVerdictService(EscrowOrderStore orderStore, Clock clock,
                                PartySignatureVerifier verifier, VerdictPrecondition precondition) {
        if (orderStore == null || clock == null || verifier == null) {
            throw new TggException("裁决编排：订单存储、时钟与验签器均不可为空");
        }
        this.orderStore = orderStore;
        this.clock = clock;
        this.verifier = verifier;
        this.precondition = precondition;
    }

    /**
     * 执行一份裁决：过守卫链后按 outcome 把订单迁到终态并落库。
     *
     * @param order      待裁决订单（须 {@code DISPUTED}）
     * @param verdict    裁决单（指向本单；{@code null} 直接拒——不当「无裁决」处理）
     * @param signatures 参与方签名集（逐方验签，验不过的方剔除）
     * @return 迁移并落库后的订单
     * @throws EscrowException 守卫链任一环不通过（含原因文案）
     */
    public EscrowOrder execute(EscrowOrder order, EscrowVerdict verdict, Map<Party, byte[]> signatures) {
        DisputeFlow.requireCanVerdict(order);
        if (precondition != null) {
            // 可选前置门（默认不设）：状态守卫之后、动任何一方之前先过——不满足即拒，绝不静默放行
            precondition.check(order);
        }
        if (verdict == null) {
            throw new EscrowException("裁决流程：裁决单未提供");
        }
        if (order.getId() == null || verdict.getOrderId() != order.getId()) {
            throw new EscrowException("裁决流程：裁决单与订单不匹配（单据订单 " + verdict.getOrderId()
                    + "，实际 " + order.getId() + "）");
        }
        requireFreshIssuedAt(verdict);
        Set<Party> verified = EscrowVerdictGuard.verifiedParties(verdict, signatures, verifier);
        if (!EscrowMultiSigRule.satisfies(verified)) {
            throw new EscrowException("裁决流程：签名不满足多签规则（须至少两方且必含联邦仲裁节点）");
        }

        Instant now = clock.instant();
        if (verdict.getOutcome() == Outcome.RELEASE) {
            order.markReleased(now);
        } else {
            order.markRefunded(verdict.getReason(), now);
        }
        // 签名集留痕（不入库——见类注释）：哪几方验签通过、裁决结果与订单号。
        log.info("裁决执行：订单 #{} outcome={} 验签通过方={}（签名集不入库，留痕于本条日志）",
                order.getId(), verdict.getOutcome(), verified);
        return orderStore.save(order);
    }

    /**
     * 签发时刻时效守卫（fail-closed）：过旧 = 陈旧/重放签名拒收，过新 = 时钟错乱拒收。
     *
     * <p>签名已绑定 canonicalBytes（含签发时刻），此守卫补的是「时间维度」：
     * 签名对内容有效，不等于这份裁决现在还该被执行。
     */
    private void requireFreshIssuedAt(EscrowVerdict verdict) {
        Instant now = clock.instant();
        Instant issuedAt = verdict.getIssuedAt();
        if (issuedAt.isBefore(now.minus(MAX_ISSUED_AGE)) || issuedAt.isAfter(now.plus(FUTURE_TOLERANCE))) {
            throw new EscrowException("裁决流程：裁决单签发时刻超出有效窗口（" + issuedAt
                    + "，当前 " + now + "）——拒绝陈旧或来自未来时刻的签名");
        }
    }
}
