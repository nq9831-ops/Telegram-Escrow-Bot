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

import com.tg.escrow.core.SilenceWindow;
import com.tg.escrow.core.UserIdMasker;
import com.tg.escrow.moderation.UserPreferencePort;
import com.tg.escrow.core.TradeGroupPort;
import com.tg.escrow.escrow.AllowedCurrencies;
import com.tg.escrow.escrow.AmountTierPolicy;
import com.tg.escrow.escrow.ConfirmGate;
import com.tg.escrow.escrow.CreditDecay;
import com.tg.escrow.escrow.CreditScore;
import com.tg.escrow.escrow.DisputeLossRepository;
import com.tg.escrow.escrow.DisputeLossStore;
import com.tg.escrow.escrow.DisputeSessionRepository;
import com.tg.escrow.escrow.DisputeSessionStore;
import com.tg.escrow.escrow.EscrowDisputeService;
import com.tg.escrow.escrow.EscrowOrder;
import com.tg.escrow.escrow.EscrowOrderLookupPort;
import com.tg.escrow.escrow.EscrowOrderRepository;
import com.tg.escrow.escrow.EscrowOrderStore;
import com.tg.escrow.escrow.EscrowTradeService;
import com.tg.escrow.escrow.EscrowVerdictService;
import com.tg.escrow.escrow.FederationPartySignatureVerifier;
import com.tg.escrow.escrow.FeePolicy;
import com.tg.escrow.escrow.JpaDisputeLossStore;
import com.tg.escrow.escrow.JpaDisputeSessionStore;
import com.tg.escrow.escrow.JpaEscrowOrderLookup;
import com.tg.escrow.escrow.JpaEscrowOrderStore;
import com.tg.escrow.escrow.JpaTradeGroupStore;
import com.tg.escrow.escrow.JpaTradeHistoryPort;
import com.tg.escrow.escrow.JpaTradeInviteStore;
import com.tg.escrow.escrow.JpaTradeReviewStore;
import com.tg.escrow.escrow.JpaTraderStatsPort;
import com.tg.escrow.escrow.MaintenanceWindow;
import com.tg.escrow.escrow.PenaltyQuery;
import com.tg.escrow.escrow.PenaltyService;
import com.tg.escrow.escrow.PendingTradeRegistry;
import com.tg.escrow.escrow.TradeAdmissionGate;
import com.tg.escrow.escrow.TradeAdmissionPolicy;
import com.tg.escrow.escrow.TradeGroupRepository;
import com.tg.escrow.escrow.TradeGroupService;
import com.tg.escrow.escrow.TradeGroupStore;
import com.tg.escrow.escrow.TradeHistoryPort;
import com.tg.escrow.escrow.TradeInviteRepository;
import com.tg.escrow.escrow.TradeInviteService;
import com.tg.escrow.escrow.TradeInviteStore;
import com.tg.escrow.escrow.TradeMaintenanceService;
import com.tg.escrow.escrow.TradeReviewRepository;
import com.tg.escrow.escrow.TradeReviewService;
import com.tg.escrow.escrow.TradeReviewStore;
import com.tg.escrow.escrow.TradeTimeoutPolicy;
import com.tg.escrow.escrow.TraderCreditService;
import com.tg.escrow.escrow.TraderStatsPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * 交易与争议域装配（ET 域）——从 {@link BotWiring} 拆出（2026-10-03 拆分时方法体逐字保留）。
 *
 * <p>覆盖：准入/费率/订单与仓储 / 邀请 / 评价 / 信用榜单 / 维护期与超时 /
 * 命令处理器 / 裁决与联邦验签 / 周榜推送 / 对手方通知。
 */
@Configuration
public class TradeWiring {

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

    /**
     * 危险动作确认槽（release/refund 二次确认）：窗口走 {@code tgg.confirm.window}（默认 PT5M）。
     *
     * <p>与 {@link #pendingTradeRegistry} 语义不同、不可混用：那个管「create→confirm <b>建单预览</b>」
     * 的登记存活（两步建单流），本槽管「已发起的<b>危险动作待确认</b>」的存活（两步确认流）。
     */
    @Bean
    public ConfirmGate confirmGate(@Value("${tgg.confirm.window:PT5M}") Duration window, Clock clock) {
        return new ConfirmGate(window, clock);
    }

    @Bean
    public EscrowOrderStore escrowOrderStore(EscrowOrderRepository repository) {
        return new JpaEscrowOrderStore(repository);
    }

    @Bean
    public TradeGroupStore tradeGroupStore(TradeGroupRepository repository) {
        return new JpaTradeGroupStore(repository);
    }

    /** 争议会话存储（Wave 1：ET-60 双方陈述 / ET-45 证据窗口的持久化）。 */
    @Bean
    public DisputeSessionStore disputeSessionStore(DisputeSessionRepository repository) {
        return new JpaDisputeSessionStore(repository);
    }

    /** 败诉台账存储（ET-61 惩罚的数据源）。 */
    @Bean
    public DisputeLossStore disputeLossStore(DisputeLossRepository repository) {
        return new JpaDisputeLossStore(repository);
    }

    /**
     * 惩罚编排（ET-61）：败诉台账 + 跨群警告累计 → {@code PenaltyPlanner} 的计划。
     * 同时作为 {@link PenaltyQuery} 供准入路径消费（{@code TRADE_LIMIT}/{@code COOLDOWN_EXTEND}）。
     */
    @Bean
    public PenaltyService penaltyService(DisputeLossStore disputeLossStore,
                                         com.tg.escrow.moderation.WarningCountQuery warningCountQuery,
                                         Clock clock) {
        return new PenaltyService(disputeLossStore, warningCountQuery, clock);
    }

    /**
     * 争议会话编排（ET-60/45）：证据窗口可配（缺省 24 小时——SPEC「争议发起后 24h 内提交」）。
     * 该窗口不随维护期暂停，故只有"多久"可配、"能否暂停"不可配（由 EvidenceDeadline 结构固化）。
     */
    @Bean
    public EscrowDisputeService escrowDisputeService(DisputeSessionStore disputeSessionStore,
                                                     @Value("${tgg.dispute.evidence-window:PT24H}") Duration evidenceWindow,
                                                     Clock clock) {
        return new EscrowDisputeService(disputeSessionStore, evidenceWindow, clock);
    }

    /** 交易群动作端口：公告/置顶/退群（Bot API 无 bot 建群能力，群由用户创建，见端口 javadoc）。 */
    @Bean
    public TradeGroupPort tradeGroupPort(TelegramClient telegramClient) {
        return new TelegramTradeGroupAdapter(telegramClient);
    }

    /**
     * 交易群编排（ET-05/06/39/40）：静默期天数可配（缺省 7 天，SPEC ET-39 口径）。
     * 群侧失败不回滚交易——降级语义在编排层（TradeCommandHandler）兑现。
     */
    @Bean
    public TradeGroupService tradeGroupService(TradeGroupStore tradeGroupStore,
                                               @Value("${tgg.trade-group.silence-days:7}") long silenceDays,
                                               Clock clock) {
        return new TradeGroupService(tradeGroupStore, Duration.ofDays(silenceDays), clock);
    }

    /**
     * 欺诈联动信号源编排（ET-04 渐进式 · 互刷源）：交易达成后评估并向管理会话投递建议。
     * 阈值默认 0.6（双向互指集中度）；通知端口为空时不投递（能力关闭，不谎报）。
     */
    @Bean
    public FraudLinkageService fraudLinkageService(TraderStatsPort statsPort,
                                                   BotReplyPort reply,
                                                   @Value("${tgg.risk.review-notify-chat:}") String reviewNotifyChat,
                                                   @Value("${tgg.fraud.concentration-threshold:0.6}") String threshold,
                                                   Clock clock) {
        // notifier 可空（null = 不投递，能力关闭）——与 review 通道同源三态
        return new FraudLinkageService(statsPort, WiringSupport.reviewNotifierOrNull(reply, reviewNotifyChat),
                clock, new BigDecimal(threshold));
    }

    /**
     * 费率策略（ET-29/30 · B1）：默认全 0 = 不收费（忘记配置=没收费，不是收错费）。
     * 三费分离：平台费放款时扣、仲裁费裁决时扣、联邦管理费从仲裁费分成。
     * 首单豁免（ET-35 · B2）：默认开——首笔成功交易免平台费；运营方要收首单费时置 false。
     */
    @Bean
    public FeePolicy feePolicy(
            @Value("${tgg.fee.platform-rate:0}") String platformRate,
            @Value("${tgg.fee.arbitration-rate:0}") String arbitrationRate,
            @Value("${tgg.fee.federation-share:0}") String federationShare,
            @Value("${tgg.fee.first-order-free:true}") boolean firstOrderFree) {
        return FeePolicy.of(platformRate, arbitrationRate, federationShare, firstOrderFree);
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

    /**
     * 本部署**允许受理**的币种（配置项 {@code tgg.allowed-currencies}，逗号分隔）。
     *
     * <p><b>留空 = 不收窄</b>（允许 {@code TradeCurrency} 全集，行为与从前一致）。
     * 写成能力白名单之外的币种（如 {@code BTC}）会在<b>启动期</b>直接抛——
     * 配置错误必须 fail-fast，而不是静默丢弃那一项。
     */
    @Bean
    public AllowedCurrencies allowedCurrencies(@Value("${tgg.allowed-currencies:}") String csv) {
        return AllowedCurrencies.fromConfig(csv);
    }

    @Bean
    public EscrowTradeService escrowTradeService(TradeAdmissionGate gate, TradeHistoryPort history,
                                                 EscrowOrderStore store,
                                                 AllowedCurrencies allowedCurrencies, Clock clock,
                                                 PenaltyQuery penaltyQuery) {
        return new EscrowTradeService(gate, history, store, allowedCurrencies, clock, penaltyQuery);
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
                                                 AllowedCurrencies allowedCurrencies, Clock clock) {
        return new TradeInviteService(gate, history, inviteStore, orderStore, ttl,
                TradeInviteService.secureRandomTokenSupplier(), allowedCurrencies, clock);
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

    /**
     * 交易者统计读取端口（信用/榜单读模型的数据入口）。
     *
     * <p>与 {@code escrowOrderStore}/{@code tradeReviewStore} 同一处装配——它们各自消费的仓储
     * 是同一批公开仓储，放一起才好一眼看清"谁在读订单表与评价表"。
     */
    @Bean
    public TraderStatsPort traderStatsPort(EscrowOrderRepository orders, TradeReviewRepository reviews) {
        return new JpaTraderStatsPort(orders, reviews);
    }

    /**
     * 信用/榜单的纯计算器。权重可配（④ 差距 1）：{@code tgg.credit.weights}（逗号分隔 5 值，
     * 顺序 = 完成数/争议率反向/好评率/对手多样性/活跃时长）；留空用默认口径 0.30/0.25/0.20/0.15/0.10。
     * 非法值（项数≠5、越界、总和≠1）在 {@link CreditScore} 构造期抛——启动即失败，不带病运行。
     *
     * <p>互刷剔除阈值（④ 差距 3）**复用** {@code tgg.fraud.concentration-threshold}——
     * 评估链说"可疑"而评分链照加，是最难查的一类自相矛盾；两处阈值漂移比没有阈值更糟。
     */
    @Bean
    public TraderCreditService traderCreditService(
            @Value("${tgg.credit.weights:}") String weights,
            @Value("${tgg.fraud.concentration-threshold:0.6}") String brushThreshold,
            @Value("${tgg.credit.decay:}") String decay,
            Clock clock) {
        return new TraderCreditService(parseWeightsOrDefault(weights), parseBrushThreshold(brushThreshold),
                parseDecayOrDefault(decay), clock);
    }

    /**
     * 解析时间衰减（④ 差距 2）：`宽限天数,步长天数,每步因子,下限`；留空 = 不衰减。
     *
     * <p>留空默认<b>不衰减</b>：衰减会改变既有账号的分数，属"宁可显式开启"的运营取舍；
     * 非法值（项数≠4/非数字/越界）由 {@link CreditDecay} 构造期抛——启动即失败。
     */
    private CreditDecay parseDecayOrDefault(String raw) {
        if (raw == null || raw.isBlank()) {
            return CreditDecay.none();
        }
        String[] parts = raw.trim().split(",");
        if (parts.length != 4) {
            throw new IllegalStateException(
                    "tgg.credit.decay 需要 4 项（宽限天数,步长天数,每步因子,下限），实为 " + parts.length + " 项");
        }
        try {
            return CreditDecay.of(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()),
                    new BigDecimal(parts[2].trim()), new BigDecimal(parts[3].trim()));
        } catch (NumberFormatException ex) {
            throw new IllegalStateException("tgg.credit.decay 含非法数字：" + raw, ex);
        }
    }

    private BigDecimal parseBrushThreshold(String raw) {
        if (raw == null || raw.isBlank()) {
            return new BigDecimal("0.6");
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException ex) {
            throw new IllegalStateException("tgg.fraud.concentration-threshold 不是合法数字：" + raw, ex);
        }
    }

    private CreditScore parseWeightsOrDefault(String raw) {
        if (raw == null || raw.isBlank()) {
            return CreditScore.defaults();
        }
        String[] parts = raw.trim().split(",");
        BigDecimal[] parsed = new BigDecimal[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String v = parts[i].trim();
            try {
                parsed[i] = new BigDecimal(v);
            } catch (NumberFormatException ex) {
                throw new IllegalStateException("tgg.credit.weights 第 " + (i + 1) + " 项不是合法数字：" + v, ex);
            }
        }
        return new CreditScore(parsed);
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

    /** 费用台账（ET-29/30）：放款核算落库，供对账（V12 fee_ledger）。 */
    @Bean
    public com.tg.escrow.escrow.FeeLedgerPort feeLedgerPort(
            com.tg.escrow.escrow.FeeLedgerRepository repository) {
        return new com.tg.escrow.escrow.JpaFeeLedgerStore(repository);
    }

    /** 链上对账（ET-19 步 2）：链上状态 → 链下订单的回填/报告（触发面 /admin/reconcile）。 */
    @Bean
    public EscrowChainReconciler escrowChainReconciler(EscrowOrderLookupPort lookup,
            EscrowOrderStore store, com.tg.escrow.chain.ChainGateway chain,
            Clock clock) {
        return new EscrowChainReconciler(lookup, store, chain, clock);
    }

    /** TON Pay 支付引用（ET-31/32）：登记与 webhook 结算痕迹（V13 tonpay_references）。 */
    @Bean
    public com.tg.escrow.escrow.TonPayReferencePort tonPayReferencePort(
            com.tg.escrow.escrow.TonPayReferenceRepository repository) {
        return new com.tg.escrow.escrow.JpaTonPayReferenceStore(repository);
    }

    @Bean
    public TradeCommandHandler tradeCommandHandler(EscrowTradeService service,
                                                   AmountTierPolicy tierPolicy,
                                                   PendingTradeRegistry pending,
                                                   ConfirmGate confirmGate,
                                                   EscrowOrderLookupPort lookup,
                                                   TradeInviteService inviteService,
                                                   InviteLink inviteLink,
                                                   TradeReviewService reviewService,
                                                   TradeMaintenanceService maintenanceService,
                                                   TradeNotifier notifier,
                                                   TraderStatsPort statsPort,
                                                   TraderCreditService credit,
                                                   AllowedCurrencies allowedCurrencies,
                                                   UserIdMasker userIdMasker,
                                                   TradeGroupService tradeGroupService,
                                                   TradeGroupPort tradeGroupPort,
                                                   FeePolicy feePolicy,
                                                   FraudLinkageService fraudLinkageService,
                                                   EscrowDisputeService disputeService,
                                                   UserPreferencePort prefs,
                                                   ChainEvidenceSink evidenceChainSink,
                                                   com.tg.escrow.escrow.FeeLedgerPort feeLedger) {
        return new TradeCommandHandler(service, tierPolicy, pending, lookup, inviteService, inviteLink,
                reviewService, maintenanceService, notifier, statsPort, credit,
                allowedCurrencies, userIdMasker, tradeGroupService, tradeGroupPort, feePolicy,
                fraudLinkageService, disputeService, prefs, evidenceChainSink, feeLedger, confirmGate);
    }

    /**
     * 裁决编排（S5 · 方案 A）——经 {@code /admin/verdict} 入口使用。
     */
    @Bean
    public EscrowVerdictService escrowVerdictService(
            EscrowOrderStore orderStore, Clock clock,
            com.tg.escrow.escrow.PartySignatureVerifier verifier,
            @Value("${tgg.dispute.require-statements:false}") boolean requireStatements,
            EscrowDisputeService disputeService) {
        // 默认不设前置门（既有裁决路径行为不变）；开启时要求"争议双方完成陈述"方可裁决（ET-60）
        return new EscrowVerdictService(orderStore, clock, verifier,
                requireStatements ? disputeService::requireStatementsComplete : null);
    }

    /**
     * 联邦验签器。未配置公钥时用「验签恒不过」占位（fail-closed：裁决一律被拒）——
     * 刻意不构造 {@link FederationPartySignatureVerifier}：它构造期拒绝空公钥（fail-fast），
     * 会让未配置的部署连启动都失败。占位 lambda 保证「未配置 = 裁决能力实质关闭」且可启动。
     */
    @Bean
    public com.tg.escrow.escrow.PartySignatureVerifier partySignatureVerifier(
            @Value("${tgg.federation.public-key:}") String federationPublicKeyBase64) {
        if (federationPublicKeyBase64 == null || federationPublicKeyBase64.isBlank()) {
            return (party, payload, signature) -> false;
        }
        return new FederationPartySignatureVerifier(federationPublicKeyBase64);
    }

    /**
     * 周榜惰性推送（④ 差距 4，ET-77）：不做调度器，群内交互触发、每周每群最多一条。
     *
     * <p>开关 {@code tgg.credit.weekly-push}（默认 true）在<b>类内</b>判定、本 bean 始终存在：
     * 让 @Bean 返回 null 会让注入方（{@code telegramBotHandler}）在缺依赖时起不来——
     * 这与坑 7「可选依赖别做成三态 bean」是同一类陷阱。
     */
    @Bean
    public WeeklyBoardPush weeklyBoardPush(TraderStatsPort statsPort, TraderCreditService credit,
                                           Clock clock,
                                           @Value("${tgg.credit.weekly-push:true}") boolean enabled,
                                           @Value("${tgg.credit.weekly-push-size:10}") int topN,
                                           UserPreferencePort prefs) {
        return new WeeklyBoardPush(statsPort, credit, clock, topN, enabled, prefs);
    }

    /**
     * 交易对手方通知器：入口层在状态迁移<b>成功后</b>调用它。
     *
     * <p>静默窗口是<b>可选</b>配置——未配置即传 {@code null}（不做静默判定），而不是给一个
     * "默认不静默的窗口"：后者会让"没配"与"配了但没命中"在代码上无法区分。
     */
    @Bean
    public TradeNotifier tradeNotifier(BotReplyPort reply, Clock clock, UserIdMasker userIdMasker,
                                       @Value("${tgg.notify.silence-start:}") String silenceStart,
                                       @Value("${tgg.notify.silence-end:}") String silenceEnd,
                                       @Value("${tgg.notify.silence-zone:UTC}") String silenceZone) {
        return new TradeNotifier(reply, clock, silenceWindow(silenceStart, silenceEnd, silenceZone),
                userIdMasker);
    }

    /** 起止任一为空即不启用静默窗口（返回 {@code null}）；跨午夜（如 22:00–02:00）由 SilenceWindow 处理。 */
    private static SilenceWindow silenceWindow(String start, String end, String zone) {
        if (start == null || start.isBlank() || end == null || end.isBlank()) {
            return null;
        }
        return new SilenceWindow(LocalTime.parse(start), LocalTime.parse(end), ZoneId.of(zone));
    }
}
