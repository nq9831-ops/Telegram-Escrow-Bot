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

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 链接过滤（原文档 G9）：检出消息中的链接，判定是否白名单、是否短链。
 *
 * <h2>三条设计选择</h2>
 * <ol>
 *   <li><b>裸域名也算链接</b>：用户常直接写 {@code example.com} 而省略 {@code https://}，
 *       只认带 scheme 的 URL 会漏掉一大类广告。</li>
 *   <li><b>子域归入白名单</b>：{@code sub.trusted.com} 视为 {@code trusted.com} 域内
 *       （{@code host.endsWith("." + allowed)}）。</li>
 *   <li><b>短链单独标注</b>：短链把真实目标藏起来，即使它落在某白名单域内也应视作可疑——
 *       所以短链判定与白名单判定分开，由调用方决定策略。</li>
 * </ol>
 *
 * <p>本类只做"检出 + 标注"，不做处置（删除/警告）——处置是策略层的事。
 */
public final class LinkFilter {

    /**
     * 链接模式：可选 scheme + 至少一个点分标签 + 至少 2 字母的 TLD + 可选端口 + 可选路径。
     * 该模式匹配"看起来像域名"的片段，不追求 RFC 完备——过滤是启发式，不是解析器。
     */
    private static final Pattern URL_PATTERN = Pattern.compile(
            "(?:https?://)?(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?\\.)+[a-zA-Z]{2,}"
                    + "(?::\\d{1,5})?(?:/[^\\s]*)?");

    private final List<String> allowedDomains;
    private final List<String> shortenerDomains;

    /**
     * @param allowedDomains   白名单域名（子域自动纳入）；{@code null} 视为空
     * @param shortenerDomains 短链域名；{@code null} 视为空
     */
    public LinkFilter(List<String> allowedDomains, List<String> shortenerDomains) {
        this.allowedDomains = normalize(allowedDomains);
        this.shortenerDomains = normalize(shortenerDomains);
    }

    /**
     * 是否配置了白名单域名。
     *
     * <p>空白名单表示<b>未配置</b>，而不是"所有域名都被禁"——调用方必须区分这两种情形：
     * 若把"未配置"当成"全禁"，默认部署下会删光每一条带链接的消息。
     */
    public boolean hasAllowedDomains() {
        return !allowedDomains.isEmpty();
    }

    /**
     * 返回文本中第一个链接的检出结果。
     *
     * @param text 消息文本
     * @return 无链接、或文本为 {@code null}/空时返回 {@link Optional#empty()}
     */
    public Optional<LinkMatch> firstLink(String text) {
        if (text == null || text.isEmpty()) {
            return Optional.empty();
        }
        Matcher matcher = URL_PATTERN.matcher(text);
        if (!matcher.find()) {
            return Optional.empty();
        }
        String url = matcher.group();
        String host = hostOf(url);
        return Optional.of(new LinkMatch(
                url, host, matchesDomain(host, allowedDomains), matchesDomain(host, shortenerDomains)));
    }

    /** 从 URL 片段剥离 scheme / 路径 / 端口，取小写主机名。 */
    private static String hostOf(String url) {
        String s = url;
        int scheme = s.indexOf("://");
        if (scheme >= 0) {
            s = s.substring(scheme + 3);
        }
        int slash = s.indexOf('/');
        if (slash >= 0) {
            s = s.substring(0, slash);
        }
        int colon = s.indexOf(':');
        if (colon >= 0) {
            s = s.substring(0, colon);
        }
        return s.toLowerCase(Locale.ROOT);
    }

    private static boolean matchesDomain(String host, List<String> domains) {
        for (String domain : domains) {
            if (host.equals(domain) || host.endsWith("." + domain)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> normalize(List<String> domains) {
        if (domains == null) {
            return List.of();
        }
        return domains.stream()
                .filter(d -> d != null && !d.isBlank())
                .map(d -> d.trim().toLowerCase(Locale.ROOT))
                .toList();
    }
}
