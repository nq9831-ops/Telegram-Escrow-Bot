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

/**
 * 持久化 V2 的装配（放在本包内：仓储是 package-private，Bean 装配必须同包可见）。
 * 生产装配由应用层 {@code tgg-app} {@code @Import} 本配置。
 */
@Configuration
public class ModerationPersistenceConfig {

    @Bean
    public WarningPort warningPort(WarningRepo repo) {
        return new JpaModerationAdapters.JpaWarningPort(repo);
    }

    @Bean
    public BannedWordStore bannedWordStore(BannedWordRepo repo) {
        return new JpaModerationAdapters.JpaBannedWordStore(repo);
    }

    @Bean
    public TradeMessageMapPort tradeMessageMapPort(TradeMessageRepo repo) {
        return new JpaModerationAdapters.JpaTradeMessageMapPort(repo);
    }

    @Bean
    public UserPreferencePort userPreferencePort(UserPrefRepo repo) {
        return new JpaModerationAdapters.JpaUserPreferencePort(repo);
    }
}
