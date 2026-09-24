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
package com.tg.escrow.webapp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mini App 前端页（{@code static/miniapp/index.html}）的存在性与契约标记测试。
 *
 * <h2>为什么静态页也要测</h2>
 * <p>页面由 Spring 以静态资源直接服务，没有编译期保障——被误删、改了脚本引址或
 * 把身份来源换成 {@code initDataUnsafe}，都<b>不会让构建变红</b>，只会在线上静默失效。
 * 本测试把"页面必须满足的几条硬契约"钉进构建：
 * <ol>
 *   <li>随 jar 打包（classpath 下确实存在）；</li>
 *   <li>加载 Telegram 官方 SDK；</li>
 *   <li>提交到 {@code /api/trade/create}；</li>
 *   <li>身份取自 {@code tg.initData} 原始串，且<b>绝不</b>用 {@code initDataUnsafe}
 *       （后者未经验签，等于把身份判断交给客户端）。</li>
 * </ol>
 */
class MiniAppPageTest {

    private static final String PAGE = "/static/miniapp/index.html";

    private static String page() throws IOException {
        try (InputStream in = MiniAppPageTest.class.getResourceAsStream(PAGE)) {
            assertThat(in)
                    .as("Mini App 页面必须随 jar 打包（classpath:%s）", PAGE)
                    .isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("页面在 classpath 下，且引了 Telegram SDK 与交易 API 路径")
    void pagePacksTelegramSdkAndApiPath() throws IOException {
        String html = page();
        assertThat(html).contains("telegram.org/js/telegram-web-app.js");
        assertThat(html).contains("/api/trade/create");
    }

    @Test
    @DisplayName("身份来自 initData 原始串，且绝不用 initDataUnsafe（安全回归守卫）")
    void identityComesFromSignedInitDataOnly() throws IOException {
        String html = page();
        assertThat(html).contains("tg.initData");
        assertThat(html)
                .as("initDataUnsafe 未经验签，出现在页面里即等于把身份判断交给客户端")
                .doesNotContain("initDataUnsafe");
    }

    @Test
    @DisplayName("提交体不含 userId 字段（身份只能来自服务端验签）")
    void payloadHasNoUserIdField() throws IOException {
        String html = page();
        assertThat(html).contains("initData:");
        assertThat(html).doesNotContain("userId");
    }
}
