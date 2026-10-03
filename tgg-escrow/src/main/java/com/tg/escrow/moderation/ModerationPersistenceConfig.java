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
package com.tg.escrow.moderation;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 持久化 V2 的装配（放在本包内：仓储是 package-private，Bean 装配必须同包可见）。
 *
 * <p><b>装配机制（实测校正）</b>：本类位于 {@code com.tg.escrow.moderation}，落在
 * {@code @SpringBootApplication}（{@code com.tg.escrow}）的组件扫描范围内，<b>生产侧靠扫描生效</b>
 * ——tgg-app 里并<b>没有</b> {@code @Import} 本配置（此前注释如此声称，与实现不符）。
 * {@code @Import} 只出现在测试装配 {@code EscrowPersistenceTestConfig} 中，那里是显式引入，
 * 因为那个测试上下文没有组件扫描。
 */
@Configuration
public class ModerationPersistenceConfig {

    /** 警告计数端口（ET-61 惩罚第一入参；读面保留——写面已随群管理摘除，计数恒 0）。 */
    @Bean
    public WarningCountQuery warningCountQuery(WarningRepo repo) {
        return new JpaWarningCountQuery(repo);
    }

    @Bean
    public TradeMessageMapPort tradeMessageMapPort(TradeMessageRepo repo, Clock clock) {
        return new JpaModerationAdapters.JpaTradeMessageMapPort(repo, clock);
    }

    @Bean
    public UserPreferencePort userPreferencePort(UserPrefRepo repo, Clock clock) {
        return new JpaModerationAdapters.JpaUserPreferencePort(repo, clock);
    }
}
