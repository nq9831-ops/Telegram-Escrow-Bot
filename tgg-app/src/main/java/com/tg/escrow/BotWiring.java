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
import com.tg.escrow.core.LinkFilter;
import com.tg.escrow.core.MediaFilter;
import com.tg.escrow.core.MemberRolePort;
import com.tg.escrow.core.MessageGuardOrchestrator;
import com.tg.escrow.core.ModerationOrchestrator;
import com.tg.escrow.core.RateLimitPolicy;
import com.tg.escrow.core.RateLimiter;
import com.tg.escrow.core.SilenceWindow;
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
import java.time.LocalTime;
import java.time.ZoneId;
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
                                                   TradeMaintenanceService maintenanceService,
                                                   TradeNotifier notifier) {
        return new TradeCommandHandler(service, tierPolicy, pending, lookup, inviteService, inviteLink,
                reviewService, maintenanceService, notifier);
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

    /** 链接过滤白名单（逗号分隔；短链域名默认可疑）。 */
    @Bean
    public LinkFilter linkFilter(
            @Value("${tgg.guard.allowed-domains:}") String allowedDomains,
            @Value("${tgg.guard.shortener-domains:t.cn,bit.ly,tinyurl.com}") String shorteners) {
        return new LinkFilter(splitCsv(allowedDomains), splitCsv(shorteners));
    }

    /** 媒体白名单（扩展名 + MIME）。 */
    @Bean
    public MediaFilter mediaFilter(
            @Value("${tgg.guard.allowed-extensions:jpg,jpeg,png,gif,pdf,zip,doc,docx,xls,xlsx,pptx}")
            String extensions,
            @Value("${tgg.guard.allowed-mime-types:image/jpeg,image/png,image/gif,application/pdf}")
            String mimeTypes) {
        return new MediaFilter(new java.util.LinkedHashSet<>(splitCsv(extensions)),
                new java.util.LinkedHashSet<>(splitCsv(mimeTypes)));
    }

    /** 三级限流（用户/群组/全局）。阈值语义由 {@code RateLimiter} 自身定义，此处只给数值。 */
    @Bean
    public RateLimiter rateLimiter(
            @Value("${tgg.guard.rate.window:PT10S}") Duration window,
            @Value("${tgg.guard.rate.user:5}") int userThreshold,
            @Value("${tgg.guard.rate.group:20}") int groupThreshold,
            @Value("${tgg.guard.rate.global:100}") int globalThreshold) {
        return new RateLimiter(new RateLimitPolicy(window, userThreshold, groupThreshold, globalThreshold));
    }

    /** 内容安全编排（链接 → 媒体 → 限流，首个命中即决胜）。 */
    @Bean
    public MessageGuardOrchestrator messageGuardOrchestrator(LinkFilter links, MediaFilter media,
                                                             RateLimiter rate, Clock clock) {
        return new MessageGuardOrchestrator(links, media, rate, clock);
    }

    /** 内容安全处置（命中 → 删消息 + 累计警告）。 */
    @Bean
    public MessageGuardService messageGuardService(MessageGuardOrchestrator guard,
                                                   GroupAdminPort admin,
                                                   WarningPort warnings) {
        return new MessageGuardService(guard, admin, warnings);
    }

    private static List<String> splitCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /** 欢迎语模板（Wave 2）：新成员入群时按模板渲染；变量缺失时保守处理由模板自身保证。 */
    @Bean
    public com.tg.escrow.core.WelcomeTemplate welcomeTemplate(
            @Value("${tgg.welcome.template:欢迎 {username} 加入 {group}！}") String template) {
        return new com.tg.escrow.core.WelcomeTemplate(template);
    }

    /**
     * 保护模式（Wave 2）：开启时应拒绝新入群。
     *
     * <p>默认<b>关闭</b>——开启会把所有人挡在门外，不该是默认状态。
     */
    @Bean
    public com.tg.escrow.core.ProtectionMode protectionMode(
            @Value("${tgg.protection.enabled:false}") boolean enabled) {
        com.tg.escrow.core.ProtectionMode mode = new com.tg.escrow.core.ProtectionMode();
        if (enabled) {
            mode.enable("启动配置 tgg.protection.enabled=true");
        }
        return mode;
    }

    /** 入群处理（保护模式 → 欢迎或拦截）。 */
    @Bean
    public MemberJoinHandler memberJoinHandler(com.tg.escrow.core.ProtectionMode protection,
                                               com.tg.escrow.core.WelcomeTemplate welcome) {
        return new MemberJoinHandler(protection, welcome);
    }

    @Bean
    public BotDispatcher botDispatcher(TradeCommandHandler tradeHandler,
                                       BannedWordRegistry bannedWords,
                                       KeywordAutoReply autoReply,
                                       ModerationCommandHandler moderationHandler,
                                       MessageGuardService guardService,
                                       @Value("${tgg.bot.username}") String botUsername) {
        return new BotDispatcher(tradeHandler, botUsername, bannedWords, autoReply, moderationHandler,
                guardService);
    }

    @Bean
    public TelegramBotHandler telegramBotHandler(BotTokenConfig tokenConfig,
                                                 BotDispatcher dispatcher,
                                                 BotReplyPort reply,
                                                 @Value("${tgg.bot.username}") String botUsername,
                                                 @Value("${tgg.webapp.url:}") String webAppUrl,
                                                 MemberRolePort memberRolePort,
                                                 MemberJoinHandler memberJoinHandler) {
        return new TelegramBotHandler(tokenConfig, dispatcher, reply, botUsername, webAppUrl,
                memberRolePort, memberJoinHandler);
    }

    /**
     * 交易对手方通知器：入口层在状态迁移<b>成功后</b>调用它。
     *
     * <p>静默窗口是<b>可选</b>配置——未配置即传 {@code null}（不做静默判定），而不是给一个
     * "默认不静默的窗口"：后者会让"没配"与"配了但没命中"在代码上无法区分。
     */
    @Bean
    public TradeNotifier tradeNotifier(BotReplyPort reply, Clock clock,
                                       @Value("${tgg.notify.silence-start:}") String silenceStart,
                                       @Value("${tgg.notify.silence-end:}") String silenceEnd,
                                       @Value("${tgg.notify.silence-zone:UTC}") String silenceZone) {
        return new TradeNotifier(reply, clock, silenceWindow(silenceStart, silenceEnd, silenceZone));
    }

    /** 起止任一为空即不启用静默窗口（返回 {@code null}）；跨午夜（如 22:00–02:00）由 SilenceWindow 处理。 */
    private static SilenceWindow silenceWindow(String start, String end, String zone) {
        if (start == null || start.isBlank() || end == null || end.isBlank()) {
            return null;
        }
        return new SilenceWindow(LocalTime.parse(start), LocalTime.parse(end), ZoneId.of(zone));
    }
}
