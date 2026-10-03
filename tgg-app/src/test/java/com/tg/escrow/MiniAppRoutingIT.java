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
package com.tg.escrow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mini App 裸路径可达性（真机测试首日实测缺陷的回归钉，2026-10-02）。
 *
 * <h2>缺陷背景</h2>
 * <p>真机操作时访问 `/miniapp`（及 `/`、`/miniapp/`）得到 Whitelabel 404——静态资源只有
 * 完整路径 `/miniapp/index.html` 可服务，而**裸路径**（BotFather 配 Main Mini App、
 * 手输 URL 时最自然的形式）全 404。本 IT 以真实 HTTP 断言修复后的重定向行为。
 *
 * <p>用 {@code RANDOM_PORT} 真起容器 + 关闭自动跟随重定向（要看 302 本身，而非它的落点）。
 */
// 同 EscrowPersistenceIT：tgg.bot.username 为必备键，测试内自带，不从环境借。
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "tgg.bot.username=it-test-bot")
class MiniAppRoutingIT {

    @LocalServerPort
    int port;

    /** Spring Boot 注入的 TestRestTemplate 默认**不跟随重定向**——正好用来断言"重定向到哪"。 */
    @Autowired
    private TestRestTemplate rest;

    /**
     * 屏蔽常驻长轮询（同 {@code EscrowPersistenceIT} 的先例）：{@code BotRunner} 是
     * {@code SmartLifecycle}，上下文启动即用 token 连真实 Telegram——dummy token 下 401
     * 会致整个上下文崩。本 IT 验证的是 HTTP 路由，不测 Telegram 连接。
     */
    @MockitoBean
    private BotRunner botRunner;

    /** 同 {@code BotRunner}：命令菜单注册器也会在启动期发起真实 Telegram 调用，用替身拦截。 */
    @MockitoBean
    private BotMenuRegistrar botMenuRegistrar;

    @Test
    @DisplayName("裸路径重定向到真实页面：/ 、/miniapp 、/miniapp/ → /miniapp/index.html")
    void barePathsRedirectToRealPage() throws Exception {
        // 用 JDK HttpClient 显式 NEVER 跟随——要断言的是 302 及其落点本身，
        // 而不是"某客户端跟随之后的千层饼"（TestRestTemplate 的跟随行为不可依赖）。
        java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                .build();
        for (String path : new String[] {"/", "/miniapp", "/miniapp/"}) {
            var resp = client.send(java.net.http.HttpRequest.newBuilder(
                            java.net.URI.create("http://127.0.0.1:" + port + path))
                    .GET().build(), java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(resp.statusCode())
                    .as("路径 %s 应重定向（实际 %s）", path, resp.statusCode()).isBetween(300, 399);
            assertThat(resp.headers().firstValue("Location").orElse(""))
                    .as("路径 %s 的落点", path).endsWith("/miniapp/index.html");
        }
    }

    @Test
    @DisplayName("真实页面自身仍 200（重定向不破坏原路径）")
    void realPageStillServes() {
        var resp = rest.getForEntity(
                "http://127.0.0.1:" + port + "/miniapp/index.html", String.class);
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(resp.getBody()).contains("TON Connect");
    }
}
