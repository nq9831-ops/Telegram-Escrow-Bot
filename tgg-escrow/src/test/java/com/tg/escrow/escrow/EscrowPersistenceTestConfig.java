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
@EntityScan(basePackageClasses = {EscrowOrder.class, com.tg.escrow.moderation.WarningPort.class})
@EnableJpaRepositories(basePackageClasses = {EscrowOrderRepository.class, com.tg.escrow.moderation.UserPreferencePort.class})
@org.springframework.context.annotation.Import(com.tg.escrow.moderation.ModerationPersistenceConfig.class)
public class EscrowPersistenceTestConfig {

    @Bean
    public EscrowOrderStore escrowOrderStore(EscrowOrderRepository repository) {
        return new JpaEscrowOrderStore(repository);
    }

    @Bean
    public TradeHistoryPort tradeHistoryPort(EscrowOrderRepository repository) {
        return new JpaTradeHistoryPort(repository);
    }

    @Bean
    public TradeInviteStore tradeInviteStore(TradeInviteRepository repository) {
        return new JpaTradeInviteStore(repository);
    }
}
