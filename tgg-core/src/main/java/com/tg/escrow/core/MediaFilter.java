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

import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 媒体过滤（原文档 G10）：按扩展名 + MIME 白名单判定媒体是否放行。
 *
 * <h2>双扩展名</h2>
 * <p>取<b>最后一个</b>点号后的扩展名。{@code invoice.jpg.exe} 的真实可执行体是 {@code exe}，
 * 若按第一个点后的 {@code jpg} 判就会被放行——这是最常见的绕过手法。
 *
 * <h2>两个维度都要过</h2>
 * <p>扩展名在白名单、且 MIME（若提供）也在白名单，才放行。单看扩展名挡不住
 * "改名成 .jpg 的可执行文件"，单看 MIME 挡不住客户端不提供 MIME 的场景。
 * 缺任一线索即 fail-closed（拒绝），不默认放行。
 */
public final class MediaFilter {

    private final Set<String> allowedExtensions;
    private final Set<String> allowedMimeTypes;

    /**
     * @param allowedExtensions 允许的扩展名（小写、不含前导点）；{@code null} 视为空
     * @param allowedMimeTypes  允许的 MIME 类型；{@code null} 视为空
     */
    public MediaFilter(Set<String> allowedExtensions, Set<String> allowedMimeTypes) {
        this.allowedExtensions = normalize(allowedExtensions, true);
        this.allowedMimeTypes = normalize(allowedMimeTypes, false);
    }

    /**
     * 判定媒体是否放行。
     *
     * @param fileName 文件名（可含路径）
     * @param mimeType 声明的 MIME 类型；{@code null}/空白时跳过 MIME 校验
     * @return 扩展名与 MIME 均通过白名单时为 {@code true}
     */
    public boolean isAllowed(String fileName, String mimeType) {
        Optional<String> ext = extensionOf(fileName);
        if (ext.isEmpty() || !allowedExtensions.contains(ext.get())) {
            return false;
        }
        if (mimeType != null && !mimeType.isBlank()) {
            return allowedMimeTypes.contains(mimeType.trim().toLowerCase(Locale.ROOT));
        }
        return true;
    }

    /** 取文件名最后一个点号之后的扩展名（小写）。无扩展名或点号结尾返回空。 */
    public Optional<String> extensionOf(String fileName) {
        if (fileName == null) {
            return Optional.empty();
        }
        String name = fileName;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return Optional.empty();
        }
        return Optional.of(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private static Set<String> normalize(Set<String> values, boolean stripLeadingDot) {
        Set<String> out = new HashSet<>();
        if (values != null) {
            for (String value : values) {
                if (value == null) {
                    continue;
                }
                String s = value.trim().toLowerCase(Locale.ROOT);
                if (stripLeadingDot && s.startsWith(".")) {
                    s = s.substring(1);
                }
                if (!s.isEmpty()) {
                    out.add(s);
                }
            }
        }
        return Set.copyOf(out);
    }
}
