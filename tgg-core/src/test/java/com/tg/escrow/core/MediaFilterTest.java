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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 媒体过滤（G10）的行为固定测试。
 *
 * <p>核心风险是<b>双扩展名绕过</b>：{@code invoice.jpg.exe} 若按第一个点后的
 * {@code jpg} 判就会被放行，而实际执行体是 {@code exe}。所以判定必须取最后一个扩展名。
 */
class MediaFilterTest {

    private static MediaFilter filter() {
        return new MediaFilter(
                Set.of("jpg", "jpeg", "png", "gif", "mp4"),
                Set.of("image/jpeg", "image/png", "image/gif", "video/mp4"));
    }

    @Test
    @DisplayName("白名单扩展名 + 白名单 MIME → 放行")
    void allowedMedia() {
        assertThat(filter().isAllowed("photo.jpg", "image/jpeg")).isTrue();
    }

    @Test
    @DisplayName("扩展名大小写不敏感")
    void extensionCaseInsensitive() {
        assertThat(filter().isAllowed("PHOTO.JPG", "image/jpeg")).isTrue();
    }

    @Test
    @DisplayName("双扩展名按最后一个判——invoice.jpg.exe 是 exe，拒绝")
    void doubleExtensionUsesLast() {
        assertThat(filter().isAllowed("invoice.jpg.exe", "image/jpeg")).isFalse();
        assertThat(filter().extensionOf("invoice.jpg.exe")).hasValue("exe");
    }

    @Test
    @DisplayName("扩展名在白名单但 MIME 不在 → 拒绝（MIME 伪造防线）")
    void mimeMustAlsoBeAllowed() {
        assertThat(filter().isAllowed("photo.jpg", "application/x-msdownload")).isFalse();
    }

    @Test
    @DisplayName("非白名单扩展名 → 拒绝")
    void disallowedExtension() {
        assertThat(filter().isAllowed("manuscript.pdf", "application/pdf")).isFalse();
    }

    @Test
    @DisplayName("无扩展名 → 拒绝（fail-closed，不能默认放行）")
    void noExtensionRejected() {
        assertThat(filter().isAllowed("README", "image/png")).isFalse();
    }

    @Test
    @DisplayName("带路径的文件只取文件名部分判扩展名")
    void pathUsesFileName() {
        assertThat(filter().isAllowed("/a/b/photo.png", "image/png")).isTrue();
        assertThat(filter().isAllowed("/a/b/evil.png.sh", null)).isFalse();
    }

    @Test
    @DisplayName("MIME 缺省（null/空白）时只按扩展名判定")
    void blankMimeSkipsMimeCheck() {
        assertThat(filter().isAllowed("photo.png", null)).isTrue();
        assertThat(filter().isAllowed("photo.png", "  ")).isTrue();
    }
}
