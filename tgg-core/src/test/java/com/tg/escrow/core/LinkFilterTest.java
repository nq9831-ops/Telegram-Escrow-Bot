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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 链接过滤（G9）的行为固定测试。
 *
 * <p>要点在于"检出"要覆盖无 scheme 的裸域名（用户常直接写 {@code example.com}），
 * 而"允许"要能识别子域（{@code sub.trusted.com} 也算白名单内）。
 */
class LinkFilterTest {

    private static LinkFilter filter() {
        return new LinkFilter(List.of("trusted.com"), List.of("bit.ly", "t.co"));
    }

    @Test
    @DisplayName("检出带 scheme 的 URL，host 正确剥离")
    void detectsSchemeUrl() {
        assertThat(filter().firstLink("看 https://evil.com/x 这里"))
                .hasValueSatisfying(m -> {
                    assertThat(m.url()).contains("evil.com");
                    assertThat(m.host()).isEqualTo("evil.com");
                    assertThat(m.allowed()).isFalse();
                });
    }

    @Test
    @DisplayName("检出无 scheme 的裸域名")
    void detectsBareDomain() {
        assertThat(filter().firstLink("访问 example.org 领取奖励"))
                .hasValueSatisfying(m -> assertThat(m.host()).isEqualTo("example.org"));
    }

    @Test
    @DisplayName("白名单域名 allowed=true，且子域也算白名单内")
    void whitelistMatchAndSubdomain() {
        assertThat(filter().firstLink("https://trusted.com/a"))
                .hasValueSatisfying(m -> assertThat(m.allowed()).isTrue());
        assertThat(filter().firstLink("https://sub.trusted.com/a"))
                .hasValueSatisfying(m -> assertThat(m.allowed()).isTrue());
    }

    @Test
    @DisplayName("主机大小写不敏感")
    void hostCaseInsensitive() {
        assertThat(filter().firstLink("https://TRUSTED.COM/a"))
                .hasValueSatisfying(m -> assertThat(m.allowed()).isTrue());
    }

    @Test
    @DisplayName("端口被保留，且不影响 host 判定")
    void portPreserved() {
        assertThat(filter().firstLink("http://trusted.com:8080/path"))
                .hasValueSatisfying(m -> {
                    assertThat(m.host()).isEqualTo("trusted.com");
                    assertThat(m.url()).contains(":8080");
                });
    }

    @Test
    @DisplayName("短链域名被标记为可疑")
    void shortenerFlagged() {
        assertThat(filter().firstLink("https://bit.ly/abc"))
                .hasValueSatisfying(m -> {
                    assertThat(m.shortener()).isTrue();
                    assertThat(m.suspicious()).isTrue();
                });
    }

    @Test
    @DisplayName("无链接返回 empty；null/空文本不抛异常")
    void noLinkReturnsEmpty() {
        assertThat(filter().firstLink("没有任何链接的消息")).isEmpty();
        assertThat(filter().firstLink(null)).isEmpty();
        assertThat(filter().firstLink("")).isEmpty();
    }
}
