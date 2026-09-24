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

import com.tg.escrow.core.BannedWordRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * tgg-app 的 Bean 装配（S1/S6）。
 *
 * <p>放在这里而不是 tgg-core：core 是纯库、不依赖 Spring——Bean 生产者属装配层职责。
 * {@link BannedWordRegistry} 需要一个生产者才能被 {@code AdminWordController} / {@code BotDispatcher} 注入
 * （缺生产者则应用上下文启动即抛 UnsatisfiedDependencyException）。
 */
@Configuration
public class BotBeans {

    @Bean
    public BannedWordRegistry bannedWordRegistry() {
        return new BannedWordRegistry();
    }
}
