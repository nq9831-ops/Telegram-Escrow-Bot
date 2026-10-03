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

import java.time.Instant;
import java.util.Map;

/**
 * 某交易者的**原始统计**（读模型的输出，尚未评分）——信用与榜单的唯一数据入口。
 *
 * <h2>它为什么单独存在</h2>
 * <p>{@link CreditScore} 只做算术：给它五个维度值，它给出 0–100。真正容易出错的是
 * **那些值怎么算出来的**——"完成"含不含退款、争议率的分母是谁、活跃天数怎么数。
 * 把原始统计收口成这个不可变记录，口径就只在一个地方定义，后面（评分、等级、榜单）
 * 都只是它的纯函数派生。
 *
 * <h2>口径（写死在这里，避免日后两套算法并存）</h2>
 * <ul>
 *   <li>{@code completed} —— 终态 {@code RELEASED} 的笔数。<b>不含</b> {@code REFUNDED} /
 *       {@code CANCELLED}：退款的交易不算"完成"。</li>
 *   <li>{@code disputeCount} / {@code totalCount} —— 争议率的分子分母。分母是
 *       <b>参与总笔数</b>（买卖任一侧、含终态与在途）。<b>已知下界</b>：状态是单值，
 *       一笔<b>已裁决</b>的争议不再显示为 {@code DISPUTED}，故本值只多不少地<b>少算</b>——
 *       要精确需事件流/历史表，属延期项（见计划 Wave 4）。</li>
 *   <li>{@code positiveReviews} / {@code reviewCount} —— 该用户<b>收到</b>的评价（评价人≠本人）。
 *       分制 1–5，{@code score ≥ 4} 计为好评。</li>
 *   <li>{@code firstAt} / {@code lastAt} —— 首笔创建与末笔更新的时刻；无历史时<b>同时为空</b>。</li>
 *   <li>{@code counterpartyCounts} —— 对手 → 笔数，喂给
 *       {@link CounterpartyDiversityScorer#maxShare}（多样性的唯一定义处）。</li>
 * </ul>
 *
 * <p>构造期拦的是**事实错误**（负数、好评多于评价总数、只给一端时刻）——它们永远不该成立，
 * 静默算出一个"看起来正常"的分数比抛异常危险得多。
 */
public record TraderStats(long userId, int completed, int disputeCount, int totalCount,
                          int positiveReviews, int reviewCount,
                          Instant firstAt, Instant lastAt,
                          Map<Long, Integer> counterpartyCounts) {

    public TraderStats {
        if (userId <= 0) {
            throw new EscrowException("交易者统计：用户 ID 非法（" + userId + "）");
        }
        if (completed < 0 || disputeCount < 0 || totalCount < 0
                || positiveReviews < 0 || reviewCount < 0) {
            throw new EscrowException("交易者统计：计数不得为负");
        }
        if (completed > totalCount) {
            throw new EscrowException("交易者统计：完成笔数不得多于参与总笔数（"
                    + completed + " > " + totalCount + "）");
        }
        if (disputeCount > totalCount) {
            throw new EscrowException("交易者统计：争议笔数不得多于参与总笔数（"
                    + disputeCount + " > " + totalCount + "）");
        }
        if (positiveReviews > reviewCount) {
            throw new EscrowException("交易者统计：好评数不可能超过评价总数（"
                    + positiveReviews + " > " + reviewCount + "）");
        }
        if ((firstAt == null) != (lastAt == null)) {
            throw new EscrowException("交易者统计：首末时刻必须同时给出或同时为空——"
                    + "只给一端时「活跃天数」无法定义");
        }
        if (firstAt != null && lastAt.isBefore(firstAt)) {
            throw new EscrowException("交易者统计：末笔时刻不得早于首笔");
        }
        counterpartyCounts = counterpartyCounts == null ? Map.of() : Map.copyOf(counterpartyCounts);
        for (Map.Entry<Long, Integer> e : counterpartyCounts.entrySet()) {
            if (e.getKey() == null || e.getValue() == null || e.getValue() < 0) {
                throw new EscrowException("交易者统计：对手笔数不得为负，且对手不可为空");
            }
        }
    }
}
