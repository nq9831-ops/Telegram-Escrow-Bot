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

import com.tg.escrow.common.TggException;

import java.util.Map;
import java.util.Set;

/**
 * 设备关联检测（ET-64）：同一设备/IP 关联多账号 → 标可疑。
 *
 * <p>与 {@link AnomalyDetector} 同族：<b>只标记不处罚</b>（交人工复核——共享设备可能是
 * 网吧/家人，误杀代价高，见 08 材料潜在冲突）。判定为<b>严格超过</b>账号阈值；
 * 空数据不臆断（NORMAL）。纯函数、无状态。
 */
public final class DeviceLinkAnalyzer {

    private DeviceLinkAnalyzer() {
    }

    /**
     * 判定设备关联是否可疑。
     *
     * @param deviceAccounts 设备/IP → 关联账号集合（不得为 {@code null}）
     * @param accountThreshold 账号数阈值（≥ 1；关联数严格超过才可疑）
     */
    public static AnomalyDetector.Verdict assess(Map<String, Set<Long>> deviceAccounts,
                                                 int accountThreshold) {
        if (deviceAccounts == null) {
            throw new TggException("设备关联：关联数据未提供");
        }
        if (accountThreshold < 1) {
            throw new TggException("设备关联：账号阈值必须 ≥ 1，实为 " + accountThreshold);
        }
        for (Set<Long> accounts : deviceAccounts.values()) {
            if (accounts == null) {
                throw new TggException("设备关联：账号集合不得为 null");
            }
            if (accounts.size() > accountThreshold) {
                return AnomalyDetector.Verdict.SUSPICIOUS;
            }
        }
        return AnomalyDetector.Verdict.NORMAL;
    }
}
