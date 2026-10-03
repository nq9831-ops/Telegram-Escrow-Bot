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

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Mini App 裸路径路由（2026-10-02 真机测试首日实测缺陷的修复）。
 *
 * <h2>缺陷</h2>
 * <p>静态资源只有完整路径 {@code /miniapp/index.html} 可服务；而**裸路径**——用户手输、
 * Bot 按钮回执、BotFather 配 Main Mini App 时最自然的形式（{@code /}、{@code /miniapp}、
 * {@code /miniapp/}）——全部 Whitelabel 404。真机实测四路径：
 * {@code / → 404}、{@code /miniapp → 404}、{@code /miniapp/ → 404}、
 * {@code /miniapp/index.html → 200}。
 *
 * <h2>修复</h2>
 * <p>三条裸路径 302 到真实页面——URL 更顺、且与 {@code SecurityConfig} 已放行的
 * {@code /miniapp} 路径语义对齐（放行了却 404 是双输）。回归钉：{@link MiniAppRoutingIT}
 * （真实 HTTP、断言 302 落点）。
 */
@Configuration
public class MiniAppRoutingConfig implements WebMvcConfigurer {

    /** Mini App 真实页面路径（唯一可服务形态）。 */
    public static final String MINIAPP_PAGE = "/miniapp/index.html";

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/", MINIAPP_PAGE);
        registry.addRedirectViewController("/miniapp", MINIAPP_PAGE);
        registry.addRedirectViewController("/miniapp/", MINIAPP_PAGE);
    }
}
