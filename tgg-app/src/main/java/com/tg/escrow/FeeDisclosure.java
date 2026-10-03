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

import com.tg.escrow.escrow.EscrowOrder;
import com.tg.escrow.escrow.FeeLedgerPort;
import com.tg.escrow.escrow.FeePolicy;
import com.tg.escrow.escrow.TraderStatsPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;

/**
 * 放款费用披露（ET-29 平台费 / ET-35 首单豁免）——从 {@link TradeCommandHandler} 拆出
 * （2026-10-03 二轮拆分，方法体逐字保留）。
 *
 * <p>费率为 0 时静默（默认不收费不该占回执）；费率非 0 时逐笔说清——首单豁免披露
 * 「首单免平台费」，否则「平台费 X、卖方实收 Y」。**平台费 ≠ 仲裁费**。
 *
 * <p>2026-10-03 增记：同口径的核算结果<b>落费用台账</b>（ET-29/30 的对账痕迹，
 * 见 {@link #recordIfReleased}）——披露给用户看，台账给运营对。
 */
final class FeeDisclosure {

    private static final Logger log = LoggerFactory.getLogger(FeeDisclosure.class);

    private final FeePolicy feePolicy;
    private final TraderStatsPort statsPort;
    /** 费用台账（ET-29/30）；{@code null} = 未装配（不记账——存量构造路径保持原语义）。 */
    private final FeeLedgerPort feeLedger;

    FeeDisclosure(FeePolicy feePolicy, TraderStatsPort statsPort) {
        this(feePolicy, statsPort, null);
    }

    FeeDisclosure(FeePolicy feePolicy, TraderStatsPort statsPort, FeeLedgerPort feeLedger) {
        this.feePolicy = feePolicy;
        this.statsPort = statsPort;
        this.feeLedger = feeLedger;
    }

    /**
     * @param sellerPriorCompleted 卖方本次之前的成功笔数（首单 = 0）；{@code null} = 统计不可得
     */
    String feeNote(EscrowOrder order, TradeEvent event, Integer sellerPriorCompleted) {
        if (event != TradeEvent.RELEASED) {
            return "";
        }
        BigDecimal amount = order.getAmount();
        BigDecimal fee = feePolicy.platformFee(amount);   // 基准（不豁免）
        if (fee.signum() == 0) {
            return "";   // 费率 0 = 不收费，静默（默认不收费不该占回执）
        }
        String carveOut = "（平台费 ≠ 仲裁费，后者仅在争议裁决时产生）";
        // 首单豁免（ET-35 · B2）：仅在统计可得且判定为首单时生效；null（统计不可得）按非首单。
        if (sellerPriorCompleted != null
                && feePolicy.platformFee(amount, sellerPriorCompleted).signum() == 0) {
            return "\n首单免平台费（应收 " + MoneyFormat.format(fee) + " " + order.getCurrency()
                    + "），卖方实收 "
                    + MoneyFormat.format(feePolicy.sellerNetOnRelease(amount, sellerPriorCompleted))
                    + " " + order.getCurrency() + carveOut;
        }
        return "\n平台费 " + MoneyFormat.format(fee) + " " + order.getCurrency()
                + "，卖方实收 " + MoneyFormat.format(feePolicy.sellerNetOnRelease(amount))
                + " " + order.getCurrency() + carveOut;
    }

    /**
     * 放款账目落库（ET-29/30）——为对账定格「这笔交易收了多少平台费」。
     *
     * <p>与 {@link #feeNote} 共用同一套计算口径（同一对重载）；记账是旁路：失败只 warn
     * 不阻断（账目缺口可被后台查询发现，不改动资金状态）。首单豁免标记的语义 =
     * 「费率非 0 但实收 0」——费率本为 0（默认不收费）不算豁免。
     *
     * @param sellerPriorCompleted 与 {@link #feeNote} 同源（迁移前快照）
     */
    void recordIfReleased(EscrowOrder order, TradeEvent event, Integer sellerPriorCompleted) {
        if (event != TradeEvent.RELEASED || feeLedger == null) {
            return;
        }
        try {
            BigDecimal amount = order.getAmount();
            BigDecimal baseFee = feePolicy.platformFee(amount);
            BigDecimal chargedFee = sellerPriorCompleted == null
                    ? baseFee
                    : feePolicy.platformFee(amount, sellerPriorCompleted);
            BigDecimal net = sellerPriorCompleted == null
                    ? feePolicy.sellerNetOnRelease(amount)
                    : feePolicy.sellerNetOnRelease(amount, sellerPriorCompleted);
            boolean waived = baseFee.signum() > 0 && chargedFee.signum() == 0;
            feeLedger.record(new FeeLedgerPort.Entry(order.getId(), order.getCurrency(),
                    amount, chargedFee, net, waived));
        } catch (RuntimeException ex) {
            log.warn("费用台账记录失败（order={}）：{}", order.getId(), ex.getMessage());
        }
    }

    /**
     * 卖方在本次交易之前的成功（RELEASED）笔数——首单判定的数据源（ET-35 · B2）。
     *
     * <p>统计属辅助通道：查询失败不得阻断放款主流程，故返回 {@code null}（「无法判定」），
     * 调用方据此按非首单收费（优惠不因异常被滥发）。取数时机见 {@code advance}（迁移之前）。
     */
    Integer priorCompletedOf(long userId) {
        try {
            int completed = statsPort.of(userId).completed();
            return completed < 0 ? null : completed;
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
