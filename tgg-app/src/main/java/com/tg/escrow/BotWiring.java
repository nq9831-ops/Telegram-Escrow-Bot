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
import com.tg.escrow.core.KeywordAutoReply;
import com.tg.escrow.escrow.AmountTierPolicy;
import com.tg.escrow.escrow.EscrowOrderLookupPort;
import com.tg.escrow.escrow.EscrowOrderRepository;
import com.tg.escrow.escrow.EscrowOrderStore;
import com.tg.escrow.escrow.EscrowTradeService;
import com.tg.escrow.escrow.JpaEscrowOrderLookup;
import com.tg.escrow.escrow.JpaEscrowOrderStore;
import com.tg.escrow.escrow.JpaTradeHistoryPort;
import com.tg.escrow.escrow.PendingTradeRegistry;
import com.tg.escrow.escrow.TradeAdmissionGate;
import com.tg.escrow.escrow.TradeAdmissionPolicy;
import com.tg.escrow.escrow.TradeHistoryPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;

/**
 * Bot 与交易链的 Bean 装配（S1 收口）。
 *
 * <h2>为什么需要它</h2>
 * <p>{@link TelegramBotHandler} 的构造链有 13 项依赖，此前除 {@link BannedWordRegistry}
 * （见 {@link BotBeans}）外<b>没有任何生产者</b>——上下文永远起不来，Bot 也就不可能收发消息。
 * 本类把整条链补齐：装配层职责留在 tgg-app，纯逻辑类（tgg-core / tgg-escrow）不被 Spring 污染。
 *
 * <h2>数值来源</h2>
 * <p>业务参数（并发上限 / 冷却期 / 分层阈值 / 待确认有效期）一律走配置项并给<b>示例默认值</b>
 * ——默认值不构成建议，部署者按自身业务覆盖（项目定位：代码回答"能不能"，配置回答"允许什么"）。
 * Bot Token 只经环境变量（{@link BotTokenConfig#from}），<b>绝不写入仓库</b>。
 */
@Configuration
public class BotWiring {

    /** 时钟：UTC，与 application.yml 的 hibernate time_zone 一致。 */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public AmountTierPolicy amountTierPolicy(
            @Value("${tgg.trade.tier.normal-max:100}") BigDecimal normalMax,
            @Value("${tgg.trade.tier.confirm-max:1000}") BigDecimal confirmMax) {
        return new AmountTierPolicy(normalMax, confirmMax);
    }

    @Bean
    public TradeAdmissionGate tradeAdmissionGate(
            @Value("${tgg.trade.concurrent-max:1}") int maxConcurrentTrades,
            @Value("${tgg.trade.cooldown:PT0S}") Duration cooldown) {
        return new TradeAdmissionGate(new TradeAdmissionPolicy(maxConcurrentTrades, cooldown));
    }

    @Bean
    public PendingTradeRegistry pendingTradeRegistry(
            @Value("${tgg.pending.ttl:PT10M}") Duration ttl, Clock clock) {
        return new PendingTradeRegistry(ttl, clock);
    }

    @Bean
    public EscrowOrderStore escrowOrderStore(EscrowOrderRepository repository) {
        return new JpaEscrowOrderStore(repository);
    }

    @Bean
    public TradeHistoryPort tradeHistoryPort(EscrowOrderRepository repository) {
        return new JpaTradeHistoryPort(repository);
    }

    /** T2 状态查询的读侧端口（与落单出口分开，见端口文档）。 */
    @Bean
    public EscrowOrderLookupPort escrowOrderLookupPort(EscrowOrderRepository repository) {
        return new JpaEscrowOrderLookup(repository);
    }

    @Bean
    public EscrowTradeService escrowTradeService(TradeAdmissionGate gate, TradeHistoryPort history,
                                                 EscrowOrderStore store, Clock clock) {
        return new EscrowTradeService(gate, history, store, clock);
    }

    /** Mini App initData 验签器（安全命门：不验签则任何人可伪造身份调交易 API）。 */
    @Bean
    public com.tg.escrow.webapp.WebAppInitDataVerifier webAppInitDataVerifier(
            BotTokenConfig tokenConfig,
            @Value("${tgg.webapp.initdata.max-age:PT1H}") Duration maxAge,
            Clock clock) {
        return new com.tg.escrow.webapp.WebAppInitDataVerifier(tokenConfig.token(), maxAge, clock);
    }

    @Bean
    public TradeCommandHandler tradeCommandHandler(EscrowTradeService service,
                                                   AmountTierPolicy tierPolicy,
                                                   PendingTradeRegistry pending,
                                                   EscrowOrderLookupPort lookup) {
        return new TradeCommandHandler(service, tierPolicy, pending, lookup);
    }

    /** GM-17 自动回复（空规则表；上线后由管理端注册关键词）。 */
    @Bean
    public KeywordAutoReply keywordAutoReply() {
        return new KeywordAutoReply();
    }

    /** token 只从环境变量读（缺失即启动失败——不静默裸奔）。 */
    @Bean
    public BotTokenConfig botTokenConfig() {
        return BotTokenConfig.from(System::getenv);
    }

    /** 回复出口：Telegram 依赖的唯一落点。 */
    @Bean
    public BotReplyPort botReplyPort(BotTokenConfig tokenConfig) {
        return new TelegramBotReplyAdapter(new OkHttpTelegramClient(tokenConfig.token()));
    }

    @Bean
    public BotDispatcher botDispatcher(TradeCommandHandler tradeHandler,
                                       BannedWordRegistry bannedWords,
                                       KeywordAutoReply autoReply,
                                       @Value("${tgg.bot.username}") String botUsername) {
        return new BotDispatcher(tradeHandler, botUsername, bannedWords, autoReply);
    }

    @Bean
    public TelegramBotHandler telegramBotHandler(BotTokenConfig tokenConfig,
                                                 BotDispatcher dispatcher,
                                                 BotReplyPort reply,
                                                 @Value("${tgg.bot.username}") String botUsername) {
        return new TelegramBotHandler(tokenConfig, dispatcher, reply, botUsername);
    }
}
