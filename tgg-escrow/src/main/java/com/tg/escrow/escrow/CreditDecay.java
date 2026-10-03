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
import java.time.Duration;
import java.time.Instant;

/**
 * 轻量时间衰减（④ 差距 2）：按「最近活跃距今」给活跃维度打折——<b>不新增任何统计数据</b>。
 *
 * <h2>为什么选轻量形态</h2>
 * <p>真衰减（每笔交易各自按期加权）需要按笔存时间戳，那是统计存储的扩张，拍板文档明言后置。
 * 现有数据里 {@code lastAt} 已经够回答"这人还活跃吗"——久未活跃的账号，其历史完成数不该
 * 与活跃账号同权，这正是衰减要表达的事。
 *
 * <h2>形态</h2>
 * <p>宽限期内不打折（默认 365 天，与「活跃满分」量表同量级）；此后每过一个步长（默认 180 天）
 * 乘一次步进因子（默认 0.9），并夹在下限（默认 0.3）之上——既让长期沉寂有代价，
 * 又不至于把历史成就一笔抹掉。
 *
 * <p><b>绝不静默</b>：因子 &lt; 1 时调用方必须把因子披露给用户（见 {@link CreditBreakdown#decayFactor()}），
 * 否则用户看到的活跃天数会比记忆里短，却查不到原因。
 *
 * <p>纯函数、无状态：{@link #factorFor} 只吃两个时刻，时钟由调用方给。
 */
public final class CreditDecay {

    /** 不衰减（因子恒 1）——默认构造与测试用，保证确定性。 */
    private static final CreditDecay NONE =
            new CreditDecay(0, Integer.MAX_VALUE, BigDecimal.ONE, BigDecimal.ONE);

    private final int graceDays;
    private final int stepDays;
    private final BigDecimal factorPerStep;
    private final BigDecimal floor;

    private CreditDecay(int graceDays, int stepDays, BigDecimal factorPerStep, BigDecimal floor) {
        this.graceDays = graceDays;
        this.stepDays = stepDays;
        this.factorPerStep = factorPerStep;
        this.floor = floor;
    }

    /** 不衰减。 */
    public static CreditDecay none() {
        return NONE;
    }

    /**
     * @param graceDays     宽限期（天）：最近活跃在此之内不打折（≥0）
     * @param stepDays      步长（天）：超出宽限后每过这么多天打一次折（≥1）
     * @param factorPerStep 每步因子（0&lt;f&lt;1）
     * @param floor         下限（0&lt;floor≤1）：因子不会低于它
     */
    public static CreditDecay of(int graceDays, int stepDays, BigDecimal factorPerStep, BigDecimal floor) {
        if (graceDays < 0) {
            throw new EscrowException("时间衰减：宽限期不得为负，实为 " + graceDays);
        }
        if (stepDays < 1) {
            throw new EscrowException("时间衰减：步长必须 ≥1 天，实为 " + stepDays);
        }
        if (factorPerStep == null || factorPerStep.signum() <= 0
                || factorPerStep.compareTo(BigDecimal.ONE) >= 0) {
            throw new EscrowException("时间衰减：步进因子必须在 (0,1)，实为 " + factorPerStep);
        }
        if (floor == null || floor.signum() <= 0 || floor.compareTo(BigDecimal.ONE) > 0) {
            throw new EscrowException("时间衰减：下限必须在 (0,1]，实为 " + floor);
        }
        return new CreditDecay(graceDays, stepDays, factorPerStep, floor);
    }

    /**
     * 最近活跃距今对应的衰减因子。
     *
     * @param lastActive 最近活跃时刻；{@code null}（无活跃史）或落在未来 → 因子 1
     * @param now        当前时刻
     */
    public BigDecimal factorFor(Instant lastActive, Instant now) {
        if (lastActive == null || now == null || !now.isAfter(lastActive)) {
            return BigDecimal.ONE;
        }
        long days = Duration.between(lastActive, now).toDays();
        if (days <= graceDays) {
            return BigDecimal.ONE;
        }
        long steps = (days - graceDays + stepDays - 1) / stepDays;   // 向上取整：超出即开始打折
        BigDecimal factor = BigDecimal.ONE;
        for (long i = 0; i < steps; i++) {
            factor = factor.multiply(factorPerStep);
            if (factor.compareTo(floor) <= 0) {
                return floor;
            }
        }
        return factor.setScale(4, RoundingMode.HALF_DOWN).max(floor);
    }
}
