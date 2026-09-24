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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 关键词自动回复（GM-17）：消息命中关键词 → 返回预设回复。
 *
 * <p>匹配语义与 {@link BannedWordMatcher} 同族：子串命中、{@code ROOT} locale 大小写折叠、
 * 命中<b>首个</b>规则即回（注册顺序稳定，结果可复现）。空白关键词拒绝——空串会命中一切。
 *
 * <p>规则集可变（管理员配置），判定时遍历快照。非线程安全（单线程配置+读取场景）。
 */
public final class KeywordAutoReply {

    private record Rule(String keyword, String reply) {
    }

    private final List<Rule> rules = new ArrayList<>();

    /**
     * 注册一条规则（追加到规则序列末尾）。
     *
     * @param keyword 关键词（不得空白）
     * @param reply   回复文本（不得空白）
     */
    public void register(String keyword, String reply) {
        if (keyword == null || keyword.isBlank()) {
            throw new TggException("自动回复：关键词不得空白（空串会命中一切）");
        }
        if (reply == null || reply.isBlank()) {
            throw new TggException("自动回复：回复文本不得空白");
        }
        rules.add(new Rule(keyword.trim(), reply));
    }

    /**
     * 查找该文本的自动回复。
     *
     * @param text 消息文本
     * @return 命中首个规则的回复；未命中或文本为空时为空
     */
    public Optional<String> replyFor(String text) {
        if (text == null || text.isEmpty()) {
            return Optional.empty();
        }
        String folded = text.toLowerCase(Locale.ROOT);
        for (Rule rule : rules) {
            if (folded.contains(rule.keyword().toLowerCase(Locale.ROOT))) {
                return Optional.of(rule.reply());
            }
        }
        return Optional.empty();
    }
}
