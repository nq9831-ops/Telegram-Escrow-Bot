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

import com.tg.escrow.common.TggException;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 费率策略（ET-29/30 · B1 拍板：费率配置化、默认 0）——纯函数，费用口径单点。
 *
 * <h2>三费分离（SPEC 6.5，对外文案依据：平台费 ≠ 仲裁费）</h2>
 * <ul>
 *   <li><b>平台费</b>：放款时按费率从流转金额扣；默认 0 = 不收费；</li>
 *   <li><b>仲裁费</b>：独立于平台费，裁决时按费率扣、<b>名义由败诉方承担</b>
 *       （费用从流转金额中扣——RELEASE 时卖方实收减少、REFUND 时买方退款减少）；</li>
 *   <li><b>联邦管理费</b>：从<b>仲裁费</b>中按比例分成（不是从平台费）。</li>
 * </ul>
 *
 * <h2>首单豁免（ET-35 · B2 拍板：免首单手续费）</h2>
 * <p>「首单保障」的最简形态——<b>首笔成功交易免平台费</b>，判定按用户 ID 首笔成功交易。
 * 豁免只作用于<b>平台费</b>（三费分离：仲裁费口径不受影响）。是否开启由 {@code firstOrderFree}
 * 决定（默认开；费率默认 0 时开关无差别）。判定入参是<b>本次之前</b>的成功交易笔数，
 * 取数与调用时机见 {@link #platformFee(BigDecimal, int)}。
 *
 * <p>默认值不替运营方定价格，但让「忘记配置」表现为<b>没收费</b>而非「收费错误」。
 * 真实划转的资金动作属 S5 链上出账；本类只负责口径（计算），调用方负责披露与记账。
 *
 * <p>费率语义为比例（{@code 0.02} = 2%），取值区间 {@code [0, 1)}——构造期 fail-fast。
 */
public final class FeePolicy {

    private static final BigDecimal ONE = BigDecimal.ONE;

    private final BigDecimal platformRate;
    private final BigDecimal arbitrationRate;
    private final BigDecimal federationShare;
    /** 首单平台费豁免是否开启（ET-35 · B2；默认开）。 */
    private final boolean firstOrderFree;

    private FeePolicy(BigDecimal platformRate, BigDecimal arbitrationRate, BigDecimal federationShare,
                      boolean firstOrderFree) {
        this.platformRate = platformRate;
        this.arbitrationRate = arbitrationRate;
        this.federationShare = federationShare;
        this.firstOrderFree = firstOrderFree;
    }

    /** 默认策略：全 0 费率（不收费）；首单豁免开启（费率 0 时开关无差别）。 */
    public static FeePolicy zero() {
        return new FeePolicy(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, true);
    }

    /**
     * 按配置构造（费率比例，如 {@code "0.02"} = 2%）；首单豁免<b>默认开启</b>（B2 拍板）。
     *
     * @param platformRate    平台费率（放款时扣）
     * @param arbitrationRate 仲裁费率（裁决时扣，名义败诉方承担）
     * @param federationShare 联邦管理费占<b>仲裁费</b>的比例
     * @throws TggException 任一费率为空、非法数字或超出 {@code [0, 1)}
     */
    public static FeePolicy of(String platformRate, String arbitrationRate, String federationShare) {
        return of(platformRate, arbitrationRate, federationShare, true);
    }

    /**
     * 按配置构造（含首单豁免开关）。
     *
     * @param firstOrderFree 是否免首单平台费（B2 拍板默认「免」；运营方要收首单费时置 false）
     * @throws TggException 任一费率非法（同三参工厂）
     */
    public static FeePolicy of(String platformRate, String arbitrationRate, String federationShare,
                               boolean firstOrderFree) {
        return new FeePolicy(parseRate("平台费率", platformRate),
                parseRate("仲裁费率", arbitrationRate),
                parseRate("联邦管理费分成", federationShare),
                firstOrderFree);
    }

    private static BigDecimal parseRate(String name, String raw) {
        if (raw == null || raw.isBlank()) {
            // 空值/未配置 = 0（同 allowed-currencies「留空=默认」惯例）——
            // 容器编排的 ${X:-} 常产出空串，把它当「没配置」而非配置错误。
            return BigDecimal.ZERO;
        }
        BigDecimal rate;
        try {
            rate = new BigDecimal(raw.trim());
        } catch (NumberFormatException ex) {
            throw new TggException("费率策略：" + name + "不是合法数字：" + raw);
        }
        if (rate.signum() < 0 || rate.compareTo(ONE) >= 0) {
            throw new TggException("费率策略：" + name + "须在 [0, 1) 区间，实为 " + rate);
        }
        return rate;
    }

    /** 首单平台费豁免是否开启（ET-35 · B2）。 */
    public boolean firstOrderFree() {
        return firstOrderFree;
    }

    /** 平台费 = 金额 × 平台费率（默认 0）。 */
    public BigDecimal platformFee(BigDecimal amount) {
        return amount.multiply(platformRate).setScale(6, RoundingMode.DOWN);
    }

    /**
     * 平台费（含首单豁免判定，ET-35 · B2）。
     *
     * <p>{@code priorCompletedCount} = <b>本次交易之前</b>该用户已达终态 {@code RELEASED} 的成功
     * 交易笔数（<b>买卖任一侧</b>参与即计入——判定按<b>用户</b>，不按角色）——0 表示这是其首笔
     * 成功交易；首单且策略开启时平台费免。豁免落在<b>本笔的平台费</b>上，而平台费由卖方承担，
     * 故实际受益方是"以卖方身份出现在本笔"的那个用户。
     *
     * <p><b>调用方必须在产生本次完成记录之前取数</b>——若在本次交易落库后再查，得到的计数
     * 已含本次，判定会把首单误判为非首单。负值是数据异常，fail-fast 而非静默当首单免费。
     *
     * @param priorCompletedCount 本次之前的历史成功笔数（不得为负）
     * @throws TggException {@code priorCompletedCount} 为负
     */
    public BigDecimal platformFee(BigDecimal amount, int priorCompletedCount) {
        if (isFirstOrderExempt(priorCompletedCount)) {
            return BigDecimal.ZERO.setScale(6, RoundingMode.DOWN);
        }
        return platformFee(amount);
    }

    /** 正常放款的卖方实收 = 金额 − 平台费（无争议不产生仲裁费）。 */
    public BigDecimal sellerNetOnRelease(BigDecimal amount) {
        return amount.subtract(platformFee(amount));
    }

    /** 正常放款的卖方实收（含首单豁免，ET-35 · B2）：金额 − 平台费（首单免时为全额）。 */
    public BigDecimal sellerNetOnRelease(BigDecimal amount, int priorCompletedCount) {
        return amount.subtract(platformFee(amount, priorCompletedCount));
    }

    /** 仲裁费 = 金额 × 仲裁费率（与平台费相互独立）。 */
    public BigDecimal arbitrationFee(BigDecimal amount) {
        return amount.multiply(arbitrationRate).setScale(6, RoundingMode.DOWN);
    }

    /** 联邦管理费 = 仲裁费 × 分成（从仲裁费出，不从平台费出）。 */
    public BigDecimal federationFee(BigDecimal amount) {
        return arbitrationFee(amount).multiply(federationShare).setScale(6, RoundingMode.DOWN);
    }

    /** 争议裁决后的实收/实退 = 金额 − 仲裁费（名义由败诉方承担，费用从流转金额中扣）。 */
    public BigDecimal netAfterArbitration(BigDecimal amount) {
        return amount.subtract(arbitrationFee(amount));
    }

    private boolean isFirstOrderExempt(int priorCompletedCount) {
        if (priorCompletedCount < 0) {
            throw new TggException("费率策略：历史成功笔数不得为负（" + priorCompletedCount
                    + "）——数据异常，拒绝据此判定首单");
        }
        return firstOrderFree && priorCompletedCount == 0;
    }
}
