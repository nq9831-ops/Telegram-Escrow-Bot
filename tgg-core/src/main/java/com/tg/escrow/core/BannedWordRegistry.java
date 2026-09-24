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

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 违禁词热更新注册表（GM-06 在线管理）——<b>按群</b>持有不可变快照。
 *
 * <h2>按群隔离（审查修复）</h2>
 * <p>每个群一份 {@link BannedWordMatcher} 快照：A 群在线改词只替换 A 的快照，
 * 不得清空 B/C 群词库（多群互清缺陷的回归防线见 {@code BannedWordRegistryTest}）。
 *
 * <h2>热更新 = 快照原子切换 + fail-safe</h2>
 * <p>{@link #reload} 重新编译后原子替换；<b>编译失败保留旧快照</b>——
 * 一次错误配置（坏正则）不该让过滤失效。匹配器本身不可变（其正则校验、空白过滤、
 * 大小写折叠已有 {@code BannedWordMatcherTest} 钉住）。
 *
 * <p>未配置词库的群不命中（"未登记=不禁"——违禁词是内容规则，与 {@code FeatureToggle}
 * 的功能开关语义不同：后者未登记=fail-closed 关闭）。
 */
public final class BannedWordRegistry {

    private final Map<Long, BannedWordMatcher> byGuild = new ConcurrentHashMap<>();

    /**
     * 热更新某群词库（Web 后台改词后调用）。
     *
     * @param guildId     群 ID（必须为正）
     * @param exactWords  精确词（不得为 {@code null}）
     * @param regexPatterns 正则（不得为 {@code null}；坏正则返回 {@code false} 且保留旧词库）
     * @return 更新是否成功
     */
    public synchronized boolean reload(long guildId, List<String> exactWords, List<String> regexPatterns) {
        if (guildId <= 0) {
            throw new TggException("违禁词注册表：群 ID 必须为正，实为 " + guildId);
        }
        if (exactWords == null || regexPatterns == null) {
            throw new TggException("违禁词注册表：词表不得为 null（空表请传 List.of()）");
        }
        BannedWordMatcher next;
        try {
            next = BannedWordMatcher.compile(exactWords, regexPatterns);
        } catch (TggException ex) {
            return false;
        }
        byGuild.put(guildId, next);
        return true;
    }

    /**
     * 判定某群消息是否命中违禁词。
     *
     * @param guildId 群 ID
     * @param text    消息文本
     * @return 命中结果；该群未配置词库或不命中时为空
     */
    public Optional<BannedWordMatcher.Match> firstMatch(long guildId, String text) {
        BannedWordMatcher matcher = byGuild.get(guildId);
        return matcher == null ? Optional.empty() : matcher.firstMatch(text);
    }
}
