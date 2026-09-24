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
import com.tg.escrow.core.GroupAdminPort;
import com.tg.escrow.core.KeywordAutoReply;
import com.tg.escrow.core.MemberRolePort;
import com.tg.escrow.core.ModerationOrchestrator;
import com.tg.escrow.core.WarningOrchestrator;
import com.tg.escrow.core.WarningPolicy;
import com.tg.escrow.moderation.WarningPort;
import com.tg.escrow.escrow.AmountTierPolicy;
import com.tg.escrow.escrow.EscrowOrder;
import com.tg.escrow.escrow.EscrowOrderLookupPort;
import com.tg.escrow.escrow.EscrowOrderRepository;
import com.tg.escrow.escrow.EscrowOrderStore;
import com.tg.escrow.escrow.MaintenanceWindow;
import com.tg.escrow.escrow.EscrowTradeService;
import com.tg.escrow.escrow.JpaEscrowOrderLookup;
import com.tg.escrow.escrow.JpaEscrowOrderStore;
import com.tg.escrow.escrow.JpaTradeHistoryPort;
import com.tg.escrow.escrow.JpaTradeInviteStore;
import com.tg.escrow.escrow.JpaTradeReviewStore;
import com.tg.escrow.escrow.PendingTradeRegistry;
import com.tg.escrow.escrow.TradeAdmissionGate;
import com.tg.escrow.escrow.TradeAdmissionPolicy;
import com.tg.escrow.escrow.TradeHistoryPort;
import com.tg.escrow.escrow.TradeInviteRepository;
import com.tg.escrow.escrow.TradeInviteService;
import com.tg.escrow.escrow.TradeInviteStore;
import com.tg.escrow.escrow.TradeMaintenanceService;
import com.tg.escrow.escrow.TradeReviewRepository;
import com.tg.escrow.escrow.TradeReviewService;
import com.tg.escrow.escrow.TradeReviewStore;
import com.tg.escrow.escrow.TradeTimeoutPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;

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

    /** 待接受邀请的存储端口（深链邀请流，Wave 1/3）。 */
    @Bean
    public TradeInviteStore tradeInviteStore(TradeInviteRepository repository) {
        return new JpaTradeInviteStore(repository);
    }

    /**
     * 深链邀请服务（建邀请 / 接单）。有效期走配置项 {@code tgg.invite.ttl}，
     * 令牌用 {@code SecureRandom} 生成（不可枚举）。
     */
    @Bean
    public TradeInviteService tradeInviteService(TradeAdmissionGate gate, TradeHistoryPort history,
                                                 TradeInviteStore inviteStore, EscrowOrderStore orderStore,
                                                 @Value("${tgg.invite.ttl:PT24H}") Duration ttl,
                                                 Clock clock) {
        return new TradeInviteService(gate, history, inviteStore, orderStore, ttl,
                TradeInviteService.secureRandomTokenSupplier(), clock);
    }

    /** 邀请深链构造器（{@code https://t.me/<bot>?startapp=<token>}）——bot 用户名来自配置。 */
    @Bean
    public InviteLink inviteLink(@Value("${tgg.bot.username}") String botUsername) {
        return new InviteLink(botUsername);
    }

    /** 交易评价存储端口（Wave 3：把「谁评过」从进程内存搬进库）。 */
    @Bean
    public TradeReviewStore tradeReviewStore(TradeReviewRepository repository) {
        return new JpaTradeReviewStore(repository);
    }

    /** 交易评价服务（守卫复用 TradeReview；从库重建「已评价方」，重启后仍拒重复评价）。 */
    @Bean
    public TradeReviewService tradeReviewService(TradeReviewStore store, Clock clock) {
        return new TradeReviewService(store, clock);
    }

    /** 维护期窗口（Wave 4）：交付后买方验收的 5 个时长选项；默认项可配。 */
    @Bean
    public MaintenanceWindow maintenanceWindow(
            @Value("${tgg.maintenance.option1:PT1H}") Duration o1,
            @Value("${tgg.maintenance.option2:PT6H}") Duration o2,
            @Value("${tgg.maintenance.option3:PT24H}") Duration o3,
            @Value("${tgg.maintenance.option4:PT72H}") Duration o4,
            @Value("${tgg.maintenance.option5:PT168H}") Duration o5,
            @Value("${tgg.maintenance.default-index:2}") int defaultIndex) {
        return new MaintenanceWindow(List.of(o1, o2, o3, o4, o5), defaultIndex);
    }

    /**
     * 超时策略（Wave 4）：交付后超过维护期 → 自动确认（放款）。
     *
     * <p>时长与 {@link MaintenanceWindow#defaultDuration()} <b>同源</b>——两处各写一份会漂移，
     * 那时"维护期还剩多久"与"何时自动放款"就会互相矛盾。
     */
    @Bean
    public TradeTimeoutPolicy tradeTimeoutPolicy(MaintenanceWindow window) {
        return new TradeTimeoutPolicy(Map.of(
                EscrowOrder.State.DELIVERED,
                new TradeTimeoutPolicy.TimeoutRule(
                        window.defaultDuration(), TradeTimeoutPolicy.TimeoutAction.AUTO_CONFIRM)));
    }

    /** 维护期服务（只判定、不执行——无调度器，超时不会自动放款）。 */
    @Bean
    public TradeMaintenanceService tradeMaintenanceService(MaintenanceWindow window,
                                                           TradeTimeoutPolicy policy, Clock clock) {
        return new TradeMaintenanceService(window, policy, clock);
    }

    @Bean
    public TradeCommandHandler tradeCommandHandler(EscrowTradeService service,
                                                   AmountTierPolicy tierPolicy,
                                                   PendingTradeRegistry pending,
                                                   EscrowOrderLookupPort lookup,
                                                   TradeInviteService inviteService,
                                                   InviteLink inviteLink,
                                                   TradeReviewService reviewService,
                                                   TradeMaintenanceService maintenanceService) {
        return new TradeCommandHandler(service, tierPolicy, pending, lookup, inviteService, inviteLink,
                reviewService, maintenanceService);
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

    /**
     * Telegram 客户端——<b>回复出口与角色查询共用同一个实例</b>（各自 new 一份会开两套连接池）。
     */
    @Bean
    public TelegramClient telegramClient(BotTokenConfig tokenConfig) {
        return new OkHttpTelegramClient(tokenConfig.token());
    }

    /** 回复出口：Telegram 依赖的唯一落点。 */
    @Bean
    public BotReplyPort botReplyPort(TelegramClient client) {
        return new TelegramBotReplyAdapter(client);
    }

    /** 群内角色查询（Wave 1）：替代此前 Bot 接线里硬编码的 MEMBER。 */
    @Bean
    public MemberRolePort memberRolePort(TelegramClient client) {
        return new TelegramMemberRoleAdapter(client);
    }

    /** 群管理动作端口（Wave 2：Telegram 实现——踢/封/禁言/删消息）。 */
    @Bean
    public GroupAdminPort groupAdminPort(TelegramClient client, Clock clock) {
        return new TelegramGroupAdminAdapter(client, clock);
    }

    /**
     * 处置编排（Wave 3：把此前零生产消费者的编排层接上线）。
     *
     * <p>联邦旁路不在此配置——未配置即 {@code fail-closed} 关闭（普通管理路径的单调守卫一行未动）。
     */
    @Bean
    public ModerationOrchestrator moderationOrchestrator(GroupAdminPort port) {
        return new ModerationOrchestrator(port);
    }

    /** 组管理命令处理器（/kick /ban /mute /del /warn /unwarn）。 */
    @Bean
    public ModerationCommandHandler moderationCommandHandler(ModerationOrchestrator moderation,
                                                            MemberRolePort roles,
                                                            WarningPort warnings,
                                                            WarningOrchestrator warningOrchestrator) {
        return new ModerationCommandHandler(moderation, roles, warnings, warningOrchestrator);
    }

    /** 警告判罚阈值（配置驱动：满 N 次禁言 / 满 M 次踢出）。 */
    @Bean
    public WarningPolicy warningPolicy(@Value("${tgg.warning.mute-threshold:3}") int muteThreshold,
                                       @Value("${tgg.warning.kick-threshold:5}") int kickThreshold) {
        return new WarningPolicy(muteThreshold, kickThreshold);
    }

    /**
     * 警告处置编排（Wave 2：接线 GM-03）。
     *
     * <p>默认禁言时长走配置 {@code tgg.warning.default-mute}。
     */
    @Bean
    public WarningOrchestrator warningOrchestrator(WarningPolicy policy,
                                                   ModerationOrchestrator moderation,
                                                   @Value("${tgg.warning.default-mute:PT10M}") Duration defaultMute) {
        return new WarningOrchestrator(policy, moderation, defaultMute);
    }

    @Bean
    public BotDispatcher botDispatcher(TradeCommandHandler tradeHandler,
                                       BannedWordRegistry bannedWords,
                                       KeywordAutoReply autoReply,
                                       ModerationCommandHandler moderationHandler,
                                       @Value("${tgg.bot.username}") String botUsername) {
        return new BotDispatcher(tradeHandler, botUsername, bannedWords, autoReply, moderationHandler);
    }

    @Bean
    public TelegramBotHandler telegramBotHandler(BotTokenConfig tokenConfig,
                                                 BotDispatcher dispatcher,
                                                 BotReplyPort reply,
                                                 @Value("${tgg.bot.username}") String botUsername,
                                                 @Value("${tgg.webapp.url:}") String webAppUrl,
                                                 MemberRolePort memberRolePort) {
        return new TelegramBotHandler(tokenConfig, dispatcher, reply, botUsername, webAppUrl, memberRolePort);
    }
}
