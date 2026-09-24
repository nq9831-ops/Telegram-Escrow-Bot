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
package com.tg.escrow.core;

/**
 * 欺诈联动（ET-04：欺诈触发群封禁）——判定结果 → 处置建议。
 *
 * <p>定死：<b>≥2 个信号叠加 → 建议封禁；1 个 → 观察名单；0 个 → 不动作</b>。
 * 单点证据不足以封禁（误判代价高，08 材料"账号异常 vs 误判"），多信号交叉才升级。
 * <b>只建议不执行</b>——执行走 {@code ModerationOrchestrator}（单调守卫），
 * 本类是情报侧，不碰处置端口。纯函数、无状态。
 */
public final class FraudLinkage {

    /** 处置建议。 */
    public enum Action {
        /** 不动作。 */
        NONE,
        /** 观察名单（单信号，继续盯）。 */
        WATCHLIST,
        /** 建议封禁（≥2 信号交叉，交人工执行）。 */
        BAN_RECOMMENDED
    }

    private FraudLinkage() {
    }

    /**
     * 按信号组合给出处置建议。
     *
     * @param mutualBrush   互刷检测可疑（{@code MutualBrushDetector}）
     * @param deviceLinked  设备关联可疑（{@code DeviceLinkAnalyzer}）
     * @param anomalySpike  行为突变可疑（{@code AnomalyDetector}）
     */
    public static Action recommend(boolean mutualBrush, boolean deviceLinked, boolean anomalySpike) {
        int signals = 0;
        if (mutualBrush) {
            signals++;
        }
        if (deviceLinked) {
            signals++;
        }
        if (anomalySpike) {
            signals++;
        }
        if (signals >= 2) {
            return Action.BAN_RECOMMENDED;
        }
        return signals == 1 ? Action.WATCHLIST : Action.NONE;
    }
}
