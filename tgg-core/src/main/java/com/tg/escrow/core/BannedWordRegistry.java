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
import java.util.Optional;

/**
 * 违禁词热更新注册表（GM-06 在线管理，06 材料的方案落地）。
 *
 * <h2>保持 {@link BannedWordMatcher} 不可变，热更新 = 快照原子切换</h2>
 * <p>匹配器本身不改（其正则校验、空白过滤、大小写折叠已有测试钉住）；本类持有一个
 * {@code volatile} 快照，{@link #reload} 重新编译后原子替换——读线程总能看到一致的词库。
 *
 * <h2>fail-safe：reload 失败保留旧词库</h2>
 * <p>新词库含非法正则时 {@link BannedWordMatcher#compile} 会抛——此时<b>保留旧快照</b>，
 * 而不是让过滤失效。一次错误配置不该把整个群暴露在"无词库"状态（与项目 fail-closed 一致）。
 */
public final class BannedWordRegistry {

    private volatile BannedWordMatcher matcher;

    /**
     * @param initial 初始词库（编译失败即抛——启动期 fail-fast）
     */
    public BannedWordRegistry(List<String> exactWords, List<String> regexPatterns) {
        this.matcher = BannedWordMatcher.compile(exactWords, regexPatterns);
    }

    /**
     * 热更新词库（Web 后台改词后调用）。
     *
     * @return 更新是否成功；{@code false} = 新词库非法，<b>保留旧词库</b>
     */
    public synchronized boolean reload(List<String> exactWords, List<String> regexPatterns) {
        BannedWordMatcher next;
        try {
            next = BannedWordMatcher.compile(exactWords, regexPatterns);
        } catch (TggException ex) {
            return false;
        }
        this.matcher = next;
        return true;
    }

    /** 判定消息是否命中违禁词（委托当前快照）。 */
    public Optional<BannedWordMatcher.Match> firstMatch(String text) {
        return matcher.firstMatch(text);
    }
}
