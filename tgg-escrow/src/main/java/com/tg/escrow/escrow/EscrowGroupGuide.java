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

import java.util.Optional;

/**
 * 交易群创建引导（ET-05）：建群 → 拉机器人+双方 → 开始交易，三步文案流。
 *
 * <p>引导有终点：末步之后 {@link #next} 返回空——不无限延伸（与 {@code JoinOnboarding}
 * 的"不反复骚扰"同一取向）。文案说人话（SPEC 7），第三步直接给命令用法。
 */
public final class EscrowGroupGuide {

    /** 引导步骤。 */
    public enum Step {
        /** 建群。 */
        CREATE_GROUP,
        /** 拉机器人与交易双方。 */
        INVITE,
        /** 开始交易。 */
        START
    }

    /**
     * 该步的引导文案。
     */
    public String promptFor(Step step) {
        if (step == null) {
            throw new EscrowException("建群引导：步骤不可为空");
        }
        return switch (step) {
            case CREATE_GROUP -> "第 1 步：新建一个群，只放这次交易的相关人。";
            case INVITE -> "第 2 步：把担保机器人和买卖双方拉进群——机器人会全程留痕。";
            case START -> "第 3 步：在群里发 /escrow create <卖方ID> <金额> <币种> 开始交易。";
        };
    }

    /**
     * 下一步；末步之后为空（引导结束）。
     */
    public Optional<Step> next(Step step) {
        if (step == null) {
            throw new EscrowException("建群引导：步骤不可为空");
        }
        return switch (step) {
            case CREATE_GROUP -> Optional.of(Step.INVITE);
            case INVITE -> Optional.of(Step.START);
            case START -> Optional.empty();
        };
    }
}
