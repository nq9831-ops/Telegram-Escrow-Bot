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
package com.tg.escrow.escrow;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * 持久化测试装配——让 {@code @DataJpaTest} 能找到 JPA 仓储并注入两个端口实现。
 *
 * <p>放在测试源里：生产装配属应用层（{@code tgg-app}）职责，本模块只提供可被装配的零件。
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@EntityScan(basePackageClasses = {EscrowOrder.class, com.tg.escrow.moderation.UserPreferencePort.class})
@EnableJpaRepositories(basePackageClasses = {EscrowOrderRepository.class, com.tg.escrow.moderation.UserPreferencePort.class})
@org.springframework.context.annotation.Import(com.tg.escrow.moderation.ModerationPersistenceConfig.class)
public class EscrowPersistenceTestConfig {

    /** 时钟：{@code ModerationPersistenceConfig} 的端口适配要求注入 Clock（不再自读系统时钟）。 */
    @Bean
    public java.time.Clock clock() {
        return java.time.Clock.systemUTC();
    }

    @Bean
    public EscrowOrderStore escrowOrderStore(EscrowOrderRepository repository) {
        return new JpaEscrowOrderStore(repository);
    }

    @Bean
    public FeeLedgerPort feeLedgerPort(FeeLedgerRepository repository) {
        return new JpaFeeLedgerStore(repository);
    }

    @Bean
    public TonPayReferencePort tonPayReferencePort(TonPayReferenceRepository repository) {
        return new JpaTonPayReferenceStore(repository);
    }

    @Bean
    public TradeHistoryPort tradeHistoryPort(EscrowOrderRepository repository) {
        return new JpaTradeHistoryPort(repository);
    }

    @Bean
    public TradeInviteStore tradeInviteStore(TradeInviteRepository repository) {
        return new JpaTradeInviteStore(repository);
    }

    @Bean
    public TradeReviewStore tradeReviewStore(TradeReviewRepository repository) {
        return new JpaTradeReviewStore(repository);
    }
}
