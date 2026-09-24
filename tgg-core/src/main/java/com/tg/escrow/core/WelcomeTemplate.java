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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 欢迎模板（原文档 G11）：把 {@code {变量}} 占位符替换为实际值。
 *
 * <h2>缺失变量一律替换掉，绝不留原文</h2>
 * <p>若变量缺失就保留 {@code {username}}，群里所有人都会看到这串模板语法——
 * 比少一个名字难看得多，也暴露了内部实现。所以缺失时替换为空串。
 *
 * <p>用 {@link Matcher#appendReplacement} + {@link Matcher#quoteReplacement}：
 * 变量值里若含 {@code $} 或 {@code \} 不会被当作正则的组引用，避免"用户名字里带 $ 就渲染错"。
 *
 * <p>本类不可变，可安全共享。
 */
public final class WelcomeTemplate {

    /** {@code {name}} 形式的占位符。 */
    private static final Pattern VAR = Pattern.compile("\\{([^}]*)}");

    private final String template;

    /**
     * @param template 模板原文（不得空白）
     */
    public WelcomeTemplate(String template) {
        if (template == null || template.isBlank()) {
            throw new TggException("欢迎模板：模板不可为空");
        }
        this.template = template;
    }

    /**
     * 渲染模板。
     *
     * @param vars 变量表；{@code null} 视为空（缺失变量替换为空串）
     * @return 渲染结果，不含任何 {@code {…}} 残留
     */
    public String render(Map<String, String> vars) {
        Map<String, String> values = vars == null ? Map.of() : vars;
        Matcher matcher = VAR.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String value = values.get(matcher.group(1));
            matcher.appendReplacement(out, Matcher.quoteReplacement(value == null ? "" : value));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
