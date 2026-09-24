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

import java.util.HashSet;
import java.util.Set;

/**
 * 功能开关（原文档 G14）：按「群组 × 功能」粒度开关。
 *
 * <h2>fail-closed：未登记即关闭</h2>
 * <p>{@link #isEnabled} 对未登记的键返回 {@code false}。这样"新功能忘了登记"
 * 表现为"开关没生效"（可立即排查），而不是"默认全群开启"（静默的功能泄漏）。
 *
 * <p>键为 {@code groupId + ":" + feature}——群组与功能两个维度都要隔离，
 * 否则一个群的开关会误伤另一个群。
 *
 * <p>非线程安全；单线程配置写入 + 读取场景适用（如需并发读，外层加同步）。
 */
public final class FeatureToggle {

    private final Set<String> enabled = new HashSet<>();

    /** 开启某群组的某功能（幂等）。 */
    public void enable(long groupId, String feature) {
        enabled.add(key(groupId, feature));
    }

    /** 关闭某群组的某功能（幂等）。 */
    public void disable(long groupId, String feature) {
        enabled.remove(key(groupId, feature));
    }

    /**
     * 该群组的该功能是否开启。
     *
     * @return 已显式开启为 {@code true}；未登记为 {@code false}（fail-closed）
     */
    public boolean isEnabled(long groupId, String feature) {
        return enabled.contains(key(groupId, feature));
    }

    private static String key(long groupId, String feature) {
        if (feature == null || feature.isBlank()) {
            throw new TggException("功能开关：功能名不可为空");
        }
        return groupId + ":" + feature;
    }
}
