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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 投票奖励分配（ET-44）：把没收的质押按权重分给投对者，管理费划转联邦，余额进仲裁基金。
 *
 * <h2>总额守恒是不变量</h2>
 * <p>核心规则「没收资金分层：补偿被挑战方 → 奖励投对者 → 划入仲裁基金」的数字实现：
 * <b>管理费 + 奖励总额 + 仲裁基金 ≡ 没收收入</b>。舍入误差一律流向仲裁基金——
 * 宁可基金多一分，不可有人凭空多发一分（违反守恒即抛，见构造后的自洽校验）。
 *
 * <p>分配口径定死：{@code reward_i = pool × w_i / Σw}（scale 2，{@link RoundingMode#HALF_DOWN}）；
 * 无投对者或权重全 0 时，奖池全额进仲裁基金（不除零、不平分）。
 *
 * <p>纯函数、无状态。
 */
public final class VoteRewardAllocator {

    /** 金额计算的定点精度（分）。 */
    private static final int SCALE = 2;

    /**
     * 分配结果。
     *
     * @param managementFee      划转联邦管理员的管理费
     * @param rewards            投对者奖励（与输入权重列表对齐）
     * @param toArbitrationFund  划入仲裁基金的余额（含舍入误差）
     */
    public record Allocation(BigDecimal managementFee, List<BigDecimal> rewards,
                             BigDecimal toArbitrationFund) {
    }

    private final BigDecimal feeRate;

    /**
     * @param managementFeeRate 管理费率，取值 [0, 1)（0 表示不收管理费）
     */
    public VoteRewardAllocator(BigDecimal managementFeeRate) {
        if (managementFeeRate == null) {
            throw new EscrowException("奖励分配：管理费率未提供");
        }
        if (managementFeeRate.signum() < 0 || managementFeeRate.compareTo(BigDecimal.ONE) >= 0) {
            throw new EscrowException("奖励分配：管理费率必须在 [0,1)，实为 " + managementFeeRate);
        }
        this.feeRate = managementFeeRate;
    }

    /**
     * 按权重分配没收收入。
     *
     * @param income         没收的质押总额（不得为负）
     * @param correctWeights 投对者的权重列表（不得含负值；空列表表示无人投对）
     * @return 分配结果（守恒：管理费 + 奖励 + 基金 ≡ 收入）
     */
    public Allocation allocate(BigDecimal income, List<BigDecimal> correctWeights) {
        if (income == null || income.signum() < 0) {
            throw new EscrowException("奖励分配：收入必须为非负金额，实为 " + income);
        }
        if (correctWeights == null) {
            throw new EscrowException("奖励分配：权重列表未提供");
        }
        for (BigDecimal w : correctWeights) {
            if (w == null || w.signum() < 0) {
                throw new EscrowException("奖励分配：权重不得为负，实为 " + w);
            }
        }

        BigDecimal fee = income.multiply(feeRate).setScale(SCALE, RoundingMode.HALF_DOWN);
        BigDecimal pool = income.subtract(fee);
        BigDecimal totalWeight = correctWeights.stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<BigDecimal> rewards = new ArrayList<>();
        BigDecimal distributed = BigDecimal.ZERO;
        for (BigDecimal w : correctWeights) {
            BigDecimal reward = totalWeight.signum() == 0
                    ? BigDecimal.ZERO.setScale(SCALE)
                    : pool.multiply(w).divide(totalWeight, SCALE, RoundingMode.HALF_DOWN);
            rewards.add(reward);
            distributed = distributed.add(reward);
        }
        BigDecimal fund = pool.subtract(distributed);

        // 守恒自洽校验：分出的每一分都有出处；舍入误差只允许流向基金
        if (fund.signum() < 0) {
            throw new EscrowException("奖励分配：总额守恒被破坏（分出 " + distributed
                    + " > 奖池 " + pool + "）");
        }
        return new Allocation(fee, List.copyOf(rewards), fund);
    }
}
