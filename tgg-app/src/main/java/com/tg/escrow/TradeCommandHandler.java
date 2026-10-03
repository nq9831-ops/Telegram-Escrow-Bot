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

import com.tg.escrow.common.EscrowException;
import com.tg.escrow.common.TggException;
import com.tg.escrow.core.BotCommand;
import com.tg.escrow.core.CommandActor;
import com.tg.escrow.core.TradeGroupPort;
import com.tg.escrow.core.UserIdMasker;
import com.tg.escrow.escrow.AllowedCurrencies;
import com.tg.escrow.escrow.AmountTierPolicy;
import com.tg.escrow.escrow.ConcurrentOrderUpdateException;
import com.tg.escrow.escrow.ConfirmGate;
import com.tg.escrow.escrow.EscrowDisputeService;
import com.tg.escrow.escrow.EscrowOrder;
import com.tg.escrow.escrow.EscrowOrderLookupPort;
import com.tg.escrow.escrow.EscrowTradeService;
import com.tg.escrow.escrow.FeeLedgerPort;
import com.tg.escrow.escrow.FeePolicy;
import com.tg.escrow.escrow.PendingTradeRegistry;
import com.tg.escrow.escrow.RiskPrompt;
import com.tg.escrow.escrow.TradeAdmissionDecision;
import com.tg.escrow.escrow.TradeCurrency;
import com.tg.escrow.escrow.TradeGroupService;
import com.tg.escrow.escrow.TradeInitiationRequest;
import com.tg.escrow.escrow.TradeInitiationResult;
import com.tg.escrow.escrow.TradeInviteService;
import com.tg.escrow.escrow.TradeMaintenanceService;
import com.tg.escrow.escrow.TradeReviewService;
import com.tg.escrow.escrow.TraderCreditService;
import com.tg.escrow.escrow.TraderStatsPort;
import com.tg.escrow.moderation.UserPreferencePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.List;

/**
 * 交易命令处理器——两步流：<b>create 预览风险提示（ET-34），confirm 校验前置后落单</b>。
 * 拆分结构（2026-10-03）：本类 = 分发器 + 核心交易流（create/confirm/lock/deliver/release/
 * refund/dispute/cancel/status 推进骨架 + 动作按钮）；争议域 → {@link DisputeCommands}、
 * 信用榜单域 → {@link CreditCommands}、邀请导航域 → {@link InviteCommands}、
 * 交易群联动 → {@link TradeGroupSideEffects}、费用披露 → {@link FeeDisclosure}、
 * 状态查询 → {@link StatusView}、解析工具 → {@link CommandParsing}。
 * 拆分各件方法体逐字保留。
 *
 * <h2>资金动作的二次确认（release / refund）</h2>
 * <p>放款与退款是不可逆的资金语义动作，命令发送与按钮点击都是单次输入——故这两个子命令走
 * <b>两步确认</b>：第 1 次输入只登记确认槽并回确认提示（<b>不执行</b>），第 2 次输入带
 * {@code --yes} 尾缀（按钮则为三段形态 {@code rl:3:y}）命中槽位才真正执行。
 * 槽位由 {@link ConfirmGate} 持有，键 =（订单号, 用户 ID）。
 */
public final class TradeCommandHandler {

    private static final Logger log = LoggerFactory.getLogger(TradeCommandHandler.class);

    private static final String COMMAND = "escrow";
    private static final String SUB_INVITE = "invite";
    private static final String SUB_CREATE = "create";
    private static final String SUB_CONFIRM = "confirm";
    private static final String SUB_STATUS = "status";
    private static final String SUB_CANCEL = "cancel";
    private static final String SUB_LOCK = "lock";
    private static final String SUB_DELIVER = "deliver";
    private static final String SUB_RELEASE = "release";
    private static final String SUB_REFUND = "refund";
    private static final String SUB_DISPUTE = "dispute";
    private static final String SUB_STATEMENT = "statement";
    private static final String SUB_READ = "read";
    private static final String SUB_EVIDENCE = "evidence";
    private static final String SUB_REVIEW = "review";
    private static final String SUB_RANK = "rank";
    private static final String SUB_CREDIT = "credit";
    private static final String SUB_MY = "my";
    private static final String SUB_GUIDE = "guide";
    /** ET-80：榜单公开 opt-in / opt-out。 */
    private static final String SUB_PUBLISH = "publish";
    private static final String SUB_UNPUBLISH = "unpublish";

    /** 「我的订单」列表最多展示条数——它是"找回订单号"的入口，同样不该刷屏。 */
    private static final int MY_ORDERS_LIMIT = 10;

    /** 列表上最多挂几个「查看」按钮——按钮太多会挤成一排点不准。 */
    private static final int MY_ORDERS_BUTTONS = 3;

    /**
     * 第二参确实是<b>订单号</b>的子命令——只有这些才按订单挂动作按钮。
     *
     * <p>用白名单而不是"凡是能解析成数字就查一下"：{@code create} / {@code confirm} 的第二参是
     * <b>卖方 ID</b>、{@code invite} 是<b>金额</b>、{@code rank} 是<b>条数</b>、{@code credit} 是
     * <b>他人 ID</b>。把它们当订单号去查，一旦数值与某个订单号撞上，就会把<b>别的订单</b>的动作
     * 按钮挂到这条回执上——用户点下去操作的是另一个单（已由测试复现）。
     */
    private static final java.util.Set<String> ORDER_ID_SUBCOMMANDS = java.util.Set.of(
            SUB_STATUS, SUB_LOCK, SUB_DELIVER, SUB_RELEASE, SUB_REFUND, SUB_DISPUTE,
            SUB_STATEMENT, SUB_READ, SUB_EVIDENCE, SUB_REVIEW, SUB_CANCEL);

    /**
     * 资金类状态迁移的诚实标注（Wave 2）：链上托管尚未接入，平台当前只做流程状态登记。
     *
     * <p>刻意随回执一起发出、并由测试断言其存在——对用户说「资金已托管」而实际分文未动，
     * 是会导致真实损失的谎报。等 S5 合约接入后，这里才是删除的时机。
     */
    public static final String CHAIN_CAVEAT =
            "⚠️ 链上托管未接入（S5 合约）；当前仅为流程状态登记，不涉及真实转账。";

    /**
     * 通知发不出去时追加给发起方的一句。
     *
     * <p>不静默失败：让发起方<b>误以为对方已收到</b>，是"主动通知"这个功能最容易造成的实质伤害。
     * 但它只是回执上的一句话，不改变迁移结果。
     */
    public static final String UNREACHABLE_HINT =
            "⚠️ 对方尚未与机器人开始对话，可能收不到通知，建议另行告知。";

    /**
     * 信用摘要的「只查自己」拒绝文案（公开，供域处理器与测试引用）。
     */
    public static final String CREDIT_SELF_ONLY =
            "信用摘要只能查自己（/escrow credit 不接受他人 ID）——榜单默认脱敏、"
                    + "公开与否由本人设置，这里也不提供按 ID 反查他人信用的入口。";

    /**
     * 用法说明文案（公开）。它与 {@link #quickUsage} 的文本都以「用法：」开头——
     * 回执层据此前缀判定"这条回执是用法说明"，从而在胶水层附上打开表单的按钮。
     */
    public static final String USAGE = "用法：/escrow invite <金额> <币种> 生成邀请链接（对方点开即接单）；"
            + "/escrow create <卖方ID> <金额> <币种> 预览风险；"
            + "确认后发 /escrow confirm <卖方ID> <金额> <币种> 创建交易；"
            + "/escrow lock <订单号> 买方托管登记；"
            + "/escrow deliver <订单号> 卖方交付；"
            + "/escrow release <订单号> 买方验收放款；"
            + "/escrow refund <订单号> [理由] 退款；"
            + "/escrow dispute <订单号> <理由> 发起争议；"
            + "/escrow statement <订单号> <陈述> 提交争议陈述（争议中，每方限一条）；"
            + "/escrow read <订单号> 阅读并确认已读对方的争议陈述；"
            + "/escrow evidence <订单号> <证据> 提交争议证据（争议中、举证窗口内、双通道）；"
            + "/escrow review <订单号> <评分1-5> 评价（仅终态交易、双方各一次）；"
            + "/escrow status <订单号> 查询订单状态；"
            + "/escrow cancel <订单号> 取消订单（仅当事人，且资金未锁仓时）；"
            + "/escrow rank [条数] 查看交易榜（默认 10 条；榜单默认脱敏，不显示完整用户 ID）；"
            + "/escrow credit 查看自己的信用摘要（评分、等级与五维拆解）；"
            + "/escrow my 查看你参与的交易（不必记订单号）；"
            + "/escrow publish 在榜单公开你的用户名（默认脱敏）；"
            + "/escrow unpublish 在榜单隐藏你的用户名；"
            + "/escrow guide 交易群创建引导（建群 → 拉人 → 群内落单）。"
            + "提示：在群里先回复对方的消息、再发 /escrow create <金额> <币种>，可免输卖方 ID";

    /**
     * 子命令级缺参提示——比 {@link #USAGE}（19 条全量）聚焦，末句指明全量入口。
     *
     * <p>缺参是最常见的输入错误；此时把用户刚碰的那条命令的用法给到、并指路全量清单，
     * 比甩一整屏更能让人找到下一步。全量文案仍是 {@link #USAGE}——无子命令或未知子命令时才回它。
     *
     * @param sub  子命令名（与 USAGE 行一致）
     * @param args 参数签名（与 USAGE 行一致，如 {@code <订单号>}）
     */
    public static String quickUsage(String sub, String args) {
        return "用法：/escrow " + sub + " " + args + "（发 /escrow 查看全部命令）";
    }

    private final EscrowTradeService service;
    private final AmountTierPolicy tierPolicy;
    private final PendingTradeRegistry pending;
    /** 危险动作确认槽（release/refund 二次确认）：第 1 发只登记、第 2 发（{@code --yes}）才放行。 */
    private final ConfirmGate confirmGate;
    private final EscrowOrderLookupPort lookup;
    private final TradeNotifier notifier;
    /**
     * 本部署的币种受理范围——create <b>预览阶段即校验</b>（③）。
     */
    private final AllowedCurrencies allowedCurrencies;
    /** 命令级日志脱敏器（④/G24：用户 ID 不落明文日志）。 */
    private final UserIdMasker userIdMasker;
    private final FraudLinkageService fraudLinkage;
    /** 争议会话编排（ET-60/45）：advance 的 dispute 分支建会话。 */
    private final EscrowDisputeService disputeService;

    /** 争议/证据/评价域处理器（拆分件）。 */
    private final DisputeCommands disputeCommands;
    /** 信用/榜单域处理器（拆分件）。 */
    private final CreditCommands creditCommands;
    /** 邀请/我的订单/引导域处理器（拆分件）。 */
    private final InviteCommands inviteCommands;
    /** 交易群联动（绑定/静默/归档，二轮拆分件）。 */
    private final TradeGroupSideEffects tradeGroupSideEffects;
    /** 放款费用披露（平台费/首单豁免，二轮拆分件）。 */
    private final FeeDisclosure feeDisclosure;
    /** 订单状态查询渲染（二轮拆分件）。 */
    private final StatusView statusView;

    /**
     * 兼容构造器用的「恒匿名」偏好：榜单始终脱敏、公开设置写入为空操作。
     * 语义等同 opt-in 落地前的旧行为——不接偏好端口的存量测试经它保持原样。
     */
    private static final UserPreferencePort ANONYMOUS_PREFS = new UserPreferencePort() {
        @Override
        public String noticeModeOf(long userId) {
            return "all";
        }

        @Override
        public void setNoticeMode(long userId, String mode) {
        }

        @Override
        public UserPreferencePort.RankVisibility rankVisibilityOf(long userId) {
            return new UserPreferencePort.RankVisibility(false, null);
        }

        @Override
        public void setRankPublic(long userId, boolean publicProfile, String username) {
        }
    };

    public TradeCommandHandler(EscrowTradeService service, AmountTierPolicy tierPolicy,
                               PendingTradeRegistry pending, EscrowOrderLookupPort lookup,
                               TradeInviteService inviteService, InviteLink inviteLink,
                               TradeReviewService reviewService,
                               TradeMaintenanceService maintenanceService,
                               TradeNotifier notifier,
                               TraderStatsPort statsPort, TraderCreditService credit,
                               AllowedCurrencies allowedCurrencies, UserIdMasker userIdMasker,
                               TradeGroupService tradeGroupService, TradeGroupPort tradeGroupPort,
                               FeePolicy feePolicy, FraudLinkageService fraudLinkage,
                               EscrowDisputeService disputeService,
                               ConfirmGate confirmGate) {
        this(service, tierPolicy, pending, lookup, inviteService, inviteLink, reviewService,
                maintenanceService, notifier, statsPort, credit, allowedCurrencies, userIdMasker,
                tradeGroupService, tradeGroupPort, feePolicy, fraudLinkage, disputeService,
                ANONYMOUS_PREFS, null, null, confirmGate);
    }

    /** 兼容构造器：不带费用台账（{@code null} = 不记账——存量调用点语义不变）。 */
    public TradeCommandHandler(EscrowTradeService service, AmountTierPolicy tierPolicy,
                               PendingTradeRegistry pending, EscrowOrderLookupPort lookup,
                               TradeInviteService inviteService, InviteLink inviteLink,
                               TradeReviewService reviewService,
                               TradeMaintenanceService maintenanceService,
                               TradeNotifier notifier,
                               TraderStatsPort statsPort, TraderCreditService credit,
                               AllowedCurrencies allowedCurrencies, UserIdMasker userIdMasker,
                               TradeGroupService tradeGroupService, TradeGroupPort tradeGroupPort,
                               FeePolicy feePolicy, FraudLinkageService fraudLinkage,
                               EscrowDisputeService disputeService,
                               UserPreferencePort prefs,
                               ChainEvidenceSink evidenceChainSink,
                               ConfirmGate confirmGate) {
        this(service, tierPolicy, pending, lookup, inviteService, inviteLink, reviewService,
                maintenanceService, notifier, statsPort, credit, allowedCurrencies,
                userIdMasker, tradeGroupService, tradeGroupPort, feePolicy, fraudLinkage,
                disputeService, prefs, evidenceChainSink, null, confirmGate);
    }

    /**
     * 规范构造器（生产装配走这条）：显式注入榜单公开偏好、链上存证腿、费用台账（可空=不记账）
     * 与动作确认槽（{@link ConfirmGate}，<b>必填</b>——缺它则二次确认会被静默跳过）。
     */
    public TradeCommandHandler(EscrowTradeService service, AmountTierPolicy tierPolicy,
                               PendingTradeRegistry pending, EscrowOrderLookupPort lookup,
                               TradeInviteService inviteService, InviteLink inviteLink,
                               TradeReviewService reviewService,
                               TradeMaintenanceService maintenanceService,
                               TradeNotifier notifier,
                               TraderStatsPort statsPort, TraderCreditService credit,
                               AllowedCurrencies allowedCurrencies, UserIdMasker userIdMasker,
                               TradeGroupService tradeGroupService, TradeGroupPort tradeGroupPort,
                               FeePolicy feePolicy, FraudLinkageService fraudLinkage,
                               EscrowDisputeService disputeService,
                               UserPreferencePort prefs,
                               ChainEvidenceSink evidenceChainSink,
                               FeeLedgerPort feeLedger,
                               ConfirmGate confirmGate) {
        if (service == null || tierPolicy == null || pending == null || lookup == null) {
            throw new TggException("命令处理：交易服务、金额分层策略、待确认登记与订单查询端口均不可为空");
        }
        if (inviteService == null || inviteLink == null) {
            throw new TggException("命令处理：邀请服务与邀请链接构造器均不可为空");
        }
        if (reviewService == null) {
            throw new TggException("命令处理：评价服务不可为空");
        }
        if (maintenanceService == null) {
            throw new TggException("命令处理：维护期服务不可为空");
        }
        if (notifier == null) {
            throw new TggException("命令处理：通知器不可为空");
        }
        if (statsPort == null || credit == null) {
            throw new TggException("命令处理：交易者统计端口与信用计算器均不可为空");
        }
        if (allowedCurrencies == null || userIdMasker == null) {
            throw new TggException("命令处理：币种受理范围与日志脱敏器均不可为空");
        }
        if (tradeGroupService == null || tradeGroupPort == null) {
            throw new TggException("命令处理：交易群编排与群动作端口均不可为空");
        }
        if (feePolicy == null) {
            throw new TggException("命令处理：费率策略不可为空");
        }
        if (fraudLinkage == null) {
            throw new TggException("命令处理：欺诈联动编排不可为空");
        }
        if (disputeService == null) {
            throw new TggException("命令处理：争议会话服务不可为空");
        }
        if (prefs == null) {
            throw new TggException("命令处理：用户偏好端口不可为空");
        }
        if (confirmGate == null) {
            throw new TggException("命令处理：动作确认槽不可为空（缺它则 release/refund 的二次确认会被静默跳过）");
        }
        this.service = service;
        this.tierPolicy = tierPolicy;
        this.pending = pending;
        this.confirmGate = confirmGate;
        this.lookup = lookup;
        this.notifier = notifier;
        this.allowedCurrencies = allowedCurrencies;
        this.userIdMasker = userIdMasker;
        this.fraudLinkage = fraudLinkage;
        this.disputeService = disputeService;
        this.disputeCommands = new DisputeCommands(lookup, disputeService, tradeGroupService,
                tradeGroupPort, reviewService, evidenceChainSink);
        this.creditCommands = new CreditCommands(statsPort, credit, prefs);
        this.inviteCommands = new InviteCommands(inviteService, inviteLink, lookup);
        this.tradeGroupSideEffects = new TradeGroupSideEffects(tradeGroupService, tradeGroupPort);
        this.feeDisclosure = new FeeDisclosure(feePolicy, statsPort, feeLedger);
        this.statusView = new StatusView(lookup, maintenanceService);
    }

    /** 本处理器是否管辖该命令。 */
    public boolean canHandle(BotCommand cmd) {
        return cmd != null && COMMAND.equals(cmd.name());
    }

    /**
     * 处理 escrow 命令（无群上下文，chatId 记 0）——见 {@link #handle(BotCommand, CommandActor, long)}。
     */
    public String handle(BotCommand cmd, CommandActor actor) {
        return handle(cmd, actor, 0L);
    }

    /**
     * 处理 escrow 命令并返回回执文案。
     *
     * @param cmd   已解析命令（须由 {@link #canHandle} 判定为管辖）
     * @param actor 执行者（发起人 = 买方）
     * @param chatId 命令所在会话（&lt; 0 = 群会话，用于交易群绑定；0 = 无群上下文/私聊）
     * @return 用户可读回执；参数缺失/非法时返回用法说明
     * @throws TggException 命令或执行者缺失、或命令不归本处理器管辖
     */
    public String handle(BotCommand cmd, CommandActor actor, long chatId) {
        // 丢弃回传槽：这条路径的调用方不关心"本次刚操作了哪一单"
        return handle(cmd, actor, chatId, new TouchSlot());
    }

    /**
     * 与 {@link #handle(BotCommand, CommandActor, long)} 相同，但把「本次刚操作的订单」回传到
     * {@code touched}。
     *
     * <p>为什么需要它：`create`/`confirm` 的回执里那句「已创建订单 #N」的 N <b>不在命令参数里</b>
     * （第二参是卖方 ID）——只有真正走到创建分支才知道它是谁。外层要据此给新单挂动作按钮，
     * 就必须把这一事实回传出去，而不是让外层去猜。
     */
    String handle(BotCommand cmd, CommandActor actor, long chatId, TouchSlot touched) {
        return handle(cmd, actor, chatId, touched, 0L);
    }

    /**
     * 完整形态：额外携带「回复目标」——回复即指定卖方
     * （{@code /escrow create <金额> <币种>}，先回复对方消息；详见 {@link #handleReply} 的说明）。
     *
     * @param replyToUserId 被回复消息的发送者 ID（0 = 无回复）——仅在显式三参形态缺席时生效
     */
    String handle(BotCommand cmd, CommandActor actor, long chatId, TouchSlot touched,
                  long replyToUserId) {
        if (cmd == null || actor == null) {
            throw new TggException("命令处理：命令与执行者不可为空");
        }
        if (!canHandle(cmd)) {
            throw new TggException("命令处理：不处理的命令 " + cmd.name());
        }

        String sub = cmd.argOpt(0).orElse(null);

        // ④ 命令级日志（脱敏）：只记子命令名与脱敏 actor——参数（对方 ID/金额/币种/订单号）一律不落日志，
        // 否则日志就成了交易明细的第二份副本。
        log.info("命令处理：/escrow {}（actor={}）", sub == null ? "(无子命令)" : sub,
                userIdMasker.mask(actor.userId()));

        // 拆分件域：争议/证据/评价、信用榜单、邀请/导航——方法体在各域处理器中逐字保留
        if (SUB_STATEMENT.equals(sub)) {
            return disputeCommands.handleStatement(cmd, actor);
        }
        if (SUB_READ.equals(sub)) {
            return disputeCommands.handleRead(cmd, actor);
        }
        if (SUB_EVIDENCE.equals(sub)) {
            return disputeCommands.handleEvidence(cmd, actor);
        }
        if (SUB_REVIEW.equals(sub)) {
            return disputeCommands.handleReview(cmd, actor);
        }
        if (SUB_RANK.equals(sub)) {
            return creditCommands.handleRank(cmd);
        }
        if (SUB_CREDIT.equals(sub)) {
            return creditCommands.handleCredit(cmd, actor);
        }
        if (SUB_PUBLISH.equals(sub)) {
            return creditCommands.handlePublish(actor, true);
        }
        if (SUB_UNPUBLISH.equals(sub)) {
            return creditCommands.handlePublish(actor, false);
        }
        if (SUB_INVITE.equals(sub)) {
            return inviteCommands.handleInvite(cmd, actor);
        }
        if (SUB_MY.equals(sub)) {
            return inviteCommands.handleMy(actor);
        }
        if (SUB_GUIDE.equals(sub)) {
            return inviteCommands.handleGuide();
        }

        // T2 状态查询 / 取消：都只吃订单号，不走 create/confirm 那套买卖双方的参数解析
        if (SUB_STATUS.equals(sub)) {
            return statusView.handleStatus(cmd);
        }
        if (SUB_CANCEL.equals(sub)) {
            return handleCancel(cmd, actor);
        }

        if (SUB_LOCK.equals(sub)) {
            return advance(cmd, actor, order -> service.lock(order, actor.userId()),
                    "托管", true, TradeEvent.LOCKED, chatId);
        }
        if (SUB_DELIVER.equals(sub)) {
            return advance(cmd, actor, order -> service.deliver(order, actor.userId()),
                    "交付", false, TradeEvent.DELIVERED, chatId);
        }
        if (SUB_RELEASE.equals(sub)) {
            // 资金动作：两步确认（第 1 发 offer / 第 2 发 --yes 才执行）——见类注释。
            return confirmThenAdvance(cmd, actor, touched, SUB_RELEASE, "rl",
                    order -> service.release(order, actor.userId()),
                    "验收放款", true, TradeEvent.RELEASED, chatId);
        }
        if (SUB_REFUND.equals(sub)) {
            String reason = refundReason(cmd);
            return confirmThenAdvance(cmd, actor, touched, SUB_REFUND, "rf",
                    order -> service.refund(order, actor.userId(), reason),
                    "退款", true, TradeEvent.REFUNDED, chatId);
        }
        if (SUB_DISPUTE.equals(sub)) {
            String reason = cmd.argOpt(2).orElse(null);
            if (reason == null || reason.isBlank()) {
                return quickUsage(SUB_DISPUTE, "<订单号> <理由>");
            }
            return advance(cmd, actor, order -> {
                EscrowOrder disputed = service.dispute(order, actor.userId(), reason);
                // 争议会话随争议发起而建——陈述/已读/证据窗口的容器（ET-60/45）。
                // 建会话失败不回滚争议（订单已成 DISPUTED 是既成事实；会话缺失由读写路径惰性补齐）。
                try {
                    disputeService.open(disputed);
                } catch (RuntimeException ex) {
                    log.warn("争议会话建立失败（订单 #{}）：{}", disputed.getId(), ex.getMessage());
                }
                return disputed;
            }, "争议", false, TradeEvent.DISPUTED, chatId);
        }

        if (!SUB_CREATE.equals(sub) && !SUB_CONFIRM.equals(sub)) {
            return USAGE;
        }

        // 回复式两参形态：先回复对方消息、再发 /escrow create <金额> <币种>——卖方取被回复者。
        // 判别用"恰好两参且回复有效"而非"arg1 能否解析成数字"：金额本身也是数字（"100"），
        // 解析成功不代表它是 ID。显式三参形态优先于回复形态（回复只是缺省时的便利）。
        boolean replyForm = replyToUserId > 0
                && cmd.argOpt(1).isPresent() && cmd.argOpt(2).isPresent()
                && cmd.argOpt(3).isEmpty();
        Long sellerId;
        BigDecimal amount;
        String currency;
        if (replyForm) {
            sellerId = replyToUserId;
            amount = CommandParsing.parseAmount(cmd.argOpt(1).orElse(null));
            currency = cmd.argOpt(2).orElse(null);
        } else {
            sellerId = CommandParsing.parseLong(cmd.argOpt(1).orElse(null));
            amount = CommandParsing.parseAmount(cmd.argOpt(2).orElse(null));
            currency = cmd.argOpt(3).orElse(null);
        }
        if (sellerId == null || amount == null || currency == null || currency.isBlank()) {
            return quickUsage(sub, "<卖方ID> <金额> <币种>");
        }

        // 参数先装配成请求：自交易 / 非正金额等事实错误在两侧一致地被拦下（异常防护对称）
        TradeInitiationRequest request;
        try {
            request = new TradeInitiationRequest(actor.userId(), sellerId, amount, currency);
        } catch (EscrowException ex) {
            return "无法创建：" + ex.getMessage();
        }

        if (SUB_CREATE.equals(sub)) {
            // ③ 预览前即校验本部署受理币种——否则风险提示会引导用户发一条注定失败的 confirm。
            try {
                allowedCurrencies.requireAllowed(TradeCurrency.requireSupported(request.currency()));
            } catch (EscrowException ex) {
                return "无法创建：" + ex.getMessage();
            }
            // ET-34：创建前强制展示风险提示——登记待确认请求，本分支不落单
            pending.prepare(request);
            String prompt = RiskPrompt.forAmount(request.amount(), tierPolicy);
            // 回复形态不把对方数字 ID 回吐给用户（那正是他不想输的东西）——按同一回复动作继续
            String confirmHint = replyForm
                    ? "\n确认无误请回复对方的同一条消息发：/escrow confirm "
                            + request.amount() + " " + request.currency()
                    : "\n确认无误请发：/escrow confirm " + request.sellerId() + " "
                            + request.amount() + " " + request.currency();
            return "⚠️ 交易前风险提示：" + prompt + confirmHint;
        }

        if (pending.consume(request).isEmpty()) {
            // 未预览 / 参数已改 / 登记过期：风险提示不可绕过
            return "请先预览风险提示再确认（未预览、参数已变或超过有效期）：\n"
                    + quickUsage(SUB_CREATE, "<卖方ID> <金额> <币种>（预览风险）");
        }

        TradeInitiationResult result;
        try {
            result = service.initiate(request);
        } catch (EscrowException ex) {
            return "无法创建：" + ex.getMessage();
        }

        if (result.status() == TradeInitiationResult.Status.CREATED) {
            // 回传"刚创建的是哪一单"：外层据此给新单挂动作按钮（它的号不在命令参数里）
            touched.touch(result.order());
            NotificationOutcome outcome = notifier.notify(result.order(), actor.userId(),
                    TradeEvent.CREATED);
            return "已创建订单 #" + result.order().getId()
                    + tradeGroupSideEffects.onTradeCreatedInGroup(result.order(), chatId)
                    + unreachableHint(outcome);
        }

        TradeAdmissionDecision decision = result.decision();
        String retry = decision.retryAfter() == null
                ? "暂无法预估"
                : decision.retryAfter().toString();
        return "被拒：" + CommandParsing.reasonText(decision.reason()) + "，可重试：" + retry;
    }

    /**
     * 推进类命令的公共骨架（lock / deliver / release / refund / dispute）：
     * 解析订单号 → 查单 → 交给服务层（权限与状态守卫都在那里）→ 统一回执。
     *
     * @param fundImplying 该迁移是否涉及资金语义——是则在回执尾部附上「链上未接入」标注
     * @param event        该迁移对应的通知事件（迁移<b>成功后</b>据此告知对手方）
     */
    private String advance(BotCommand cmd, CommandActor actor, OrderStep step,
                           String action, boolean fundImplying, TradeEvent event, long chatId) {
        Long orderId = CommandParsing.parseLong(cmd.argOpt(1).orElse(null));
        if (orderId == null) {
            return quickUsage(cmd.argOpt(0).orElse(""), "<订单号>");
        }
        EscrowOrder order = lookup.byId(orderId).orElse(null);
        if (order == null) {
            return "订单 #" + orderId + " 不存在（请核对订单号）";
        }
        // 首单判定（ET-35 · B2）必须在迁移之前取数——放款落库后再查，统计已含本次，
        // 首单会被误判为「非首单」而多收费。仅放款语义下取，其余迁移不付这次聚合查询。
        Integer sellerPriorCompleted = event == TradeEvent.RELEASED
                ? feeDisclosure.priorCompletedOf(order.getSellerUserId()) : null;
        try {
            step.run(order);
        } catch (ConcurrentOrderUpdateException ex) {
            return "订单 #" + orderId + " 已被他人变更，请重查后再操作";
        } catch (EscrowException ex) {
            // 静默期内再争议（ET-40）：订单已终态拒争议是正常守卫，但群侧要把静默拉回留痕。
            String silenceNote = TradeEvent.DISPUTED == event
                    ? tradeGroupSideEffects.disputeInSilenceGroup(orderId) : "";
            return "无法" + action + "：" + ex.getMessage() + silenceNote;
        }
        // 迁移已提交，此时才通知——通知失败绝不改变上面已落库的结果（见 UNREACHABLE_HINT）
        NotificationOutcome outcome = notifier.notify(order, actor.userId(), event);
        String groupNote = tradeGroupSideEffects.settleGroupIfNeeded(order, event);
        String feeNote = feeDisclosure.feeNote(order, event, sellerPriorCompleted);
        // 费用台账（ET-29/30）：放款核算落库为对账痕迹（旁路——失败不阻断本回执）
        feeDisclosure.recordIfReleased(order, event, sellerPriorCompleted);
        if (event == TradeEvent.RELEASED && chatId < 0) {
            // 交易达成后做欺诈评估（ET-04 渐进式）——只投递建议给管理员，不占用户回执（安静原则）
            try {
                fraudLinkage.assess(chatId, order.getBuyerUserId(), order.getSellerUserId());
            } catch (RuntimeException ex) {
                // 评估失败绝不影响放款主流程（辅助通道语义，同 TradeNotifier）
            }
        }
        String tail = (fundImplying ? "\n" + CHAIN_CAVEAT : "") + unreachableHint(outcome);
        return "订单 #" + orderId + " 已" + actionSucceeded(action) + "。" + groupNote + feeNote + tail;
    }

    /**
     * 命令尾部的确认标记：仅当<b>最后一个参数</b>逐字为 {@code --yes} 才算「第 2 发」。
     *
     * <p>刻意只认尾部：{@code --yes} 出现在中段（如 {@code /escrow release --yes 5}）时不识别，
     * 避免"标记与参数混在一起"时误判为已确认——宁可多问一次，不可少问一次。
     */
    private static boolean hasTrailingYes(BotCommand cmd) {
        int last = cmd.argCount() - 1;
        return last >= 0 && "--yes".equals(cmd.argOpt(last).orElse(null));
    }

    /**
     * 退款理由：尾部 {@code --yes} 是<b>确认标记、不是理由</b>——取理由位时把它排除，缺失给默认文案。
     *
     * <p>否则 {@code /escrow refund 5 --yes} 会把「--yes」当成退款理由烙进通知文案。
     */
    private static String refundReason(BotCommand cmd) {
        String reason = cmd.argOpt(2).orElse(null);
        if (reason == null || "--yes".equals(reason)) {
            return "当事人协商退款";
        }
        return reason;
    }

    /**
     * 资金动作（release / refund）的两步确认骨架（按钮与命令共用同一条路径）：
     *
     * <ol>
     *   <li>第 1 发（无 {@code --yes} 尾缀）≡ 第 1 击 → <b>不执行</b>：登记确认槽 + 回确认提示；</li>
     *   <li>第 2 发（{@code --yes} 尾缀）≡ 第 2 击（三段形态 {@code rl:N:y}）→ 命中槽位才执行；
     *       未命中（未发起 / 已过期 / 已消费）则提示重新发起。</li>
     * </ol>
     *
     * <p>第 1 步通过 {@code touched} 把「本次刚发起的待确认动作」回传给外层，由 outer 给这条
     * offer 挂「确认执行」按钮——否则第 2 击在实现层不可达（{@code buttonsFor} 的三源都不覆盖 offer 回执）。
     */
    private String confirmThenAdvance(BotCommand cmd, CommandActor actor, TouchSlot touched,
                                      String sub, String verb, OrderStep step, String action,
                                      boolean fundImplying, TradeEvent event, long chatId) {
        Long orderId = CommandParsing.parseLong(cmd.argOpt(1).orElse(null));
        if (orderId == null) {
            return quickUsage(sub, "<订单号>");
        }
        if (!hasTrailingYes(cmd)) {
            confirmGate.offer(orderId, actor.userId(), verb);
            touched.expectConfirm(orderId, verb);
            return "⚠️ 这是资金动作，需要二次确认。订单 #" + orderId + " 的「" + action
                    + "」尚未执行——请再发一次确认：\n/escrow " + sub + " " + orderId + " --yes";
        }
        if (!confirmGate.consume(orderId, actor.userId(), verb)) {
            return "确认已过期或未发起（资金动作需二次确认，请重新发起）：\n"
                    + quickUsage(sub, "<订单号> --yes");
        }
        return advance(cmd, actor, step, action, fundImplying, event, chatId);
    }

    /**
     * 与 {@link #handle} 相同，但把回执包成 {@link BotReply}——可携带<b>动作按钮</b>。
     *
     * <p>用法回执（{@link #USAGE}）保持"引导去表单"的既有形态、不挂动作按钮——那时用户
     * 还没有可操作的订单。
     */
    public BotReply handleReply(BotCommand cmd, CommandActor actor, long chatId) {
        return handleReply(cmd, actor, chatId, 0L);
    }

    /**
     * 带「回复目标」的回执形态——{@code TelegramBotHandler} 把消息的回复信息透传到这里。
     *
     * <p>回复即指定卖方：在群里先回复对方的消息、再发 {@code /escrow create <金额> <币种>}
     * （两个参数），卖方取被回复者。Telegram 客户端不展示数字 ID——让用户去第三方查 ID
     * 是创建交易最大的门槛，回复是生态里的标准做法；显式三参形态不受影响（参数优先）。
     */
    public BotReply handleReply(BotCommand cmd, CommandActor actor, long chatId,
                                long replyToUserId) {
        TouchSlot touched = new TouchSlot();
        String text = handle(cmd, actor, chatId, touched, replyToUserId);
        // 用法类回执（全量 USAGE 与子命令短提示都以「用法：」开头）引导去表单、不挂动作按钮——
        // 那时用户还没有可操作的订单。
        if (text == null || text.startsWith("用法：")) {
            return BotReply.withWebApp(text);
        }
        return BotReply.withActions(text, buttonsFor(cmd, actor, touched));
    }

    /**
     * 「本次命令刚操作的订单」的回传槽——只在同一次 {@code handle} 调用内有效。
     *
     * <p>刻意做成方法内的一次性容器而不是实例字段：实例字段会让并发命令互相覆盖，
     * 而回执是<b>逐条</b>发出的——这类"刚才那单"的信息必须与产生它的那次调用同生命周期。
     */
    static final class TouchSlot {

        private Long orderId;
        /** 非 {@code null} = 本次回执是一条「待确认 offer」，值为确认所需的动作缩写（{@code rl}/{@code rf}）。 */
        private String confirmVerb;

        void touch(EscrowOrder order) {
            if (order != null) {
                this.orderId = order.getId();
            }
        }

        /** 登记一条待确认 offer——回执据此挂「确认执行」按钮（第 2 击入口）。 */
        void expectConfirm(long orderId, String confirmVerb) {
            this.orderId = orderId;
            this.confirmVerb = confirmVerb;
        }

        /** 本次调用刚操作的订单号；没有则为 {@code null}。 */
        Long orderId() {
            return orderId;
        }

        /** 本次调用待确认的动作缩写；不是 offer 时为 {@code null}。 */
        String confirmVerb() {
            return confirmVerb;
        }
    }

    /**
     * 为本次命令挑动作按钮——按钮只是<b>提示</b>，能不能做由服务层守卫判定。
     *
     * <p>两处刻意留白：只给<b>当事人</b>的订单挂；订单查不到 / 未落库 → 空按钮。
     */
    private List<BotReply.ActionButton> buttonsFor(BotCommand cmd, CommandActor actor, TouchSlot touched) {
        // 最优先：本次刚发起的「待确认 offer」——挂「确认执行」按钮（三段形态），让第 2 击可达。
        // 与 touched.orderId() 分支分开：offer 回执要的不是状态卡动作按钮，而是那枚确认按钮本身。
        String confirmVerb = touched.confirmVerb();
        Long touchedForConfirm = touched.orderId();
        if (confirmVerb != null && touchedForConfirm != null) {
            return List.of(ActionButtons.confirmButton(touchedForConfirm, confirmVerb));
        }
        // 其次：本次刚创建/刚操作的那一单——它的号不在命令参数里，由 handle 内部回传。
        Long touchedOrderId = touched.orderId();
        if (touchedOrderId != null) {
            return lookup.byId(touchedOrderId)
                    .map(order -> ActionButtons.forOrder(order, actor.userId()))
                    .orElse(List.of());
        }
        String sub = cmd.argOpt(0).orElse(null);
        if (SUB_MY.equals(sub)) {
            // 列表：给最近几单各挂一个「查看」——点开才是那张带动作的状态卡
            return lookup.recentFor(actor.userId(), MY_ORDERS_LIMIT).stream()
                    .limit(MY_ORDERS_BUTTONS)
                    .map(order -> ActionButtons.viewButton(order.getId()))
                    .toList();
        }
        if (!ORDER_ID_SUBCOMMANDS.contains(sub)) {
            // 第二参不是订单号（卖方 ID / 金额 / 条数…）——据此查单会挂出别人的按钮
            return List.of();
        }
        Long orderId = CommandParsing.parseLong(cmd.argOpt(1).orElse(null));
        if (orderId == null) {
            return List.of();
        }
        return lookup.byId(orderId)
                .filter(order -> order.getBuyerUserId() == actor.userId()
                        || order.getSellerUserId() == actor.userId())
                .map(order -> ActionButtons.forOrder(order, actor.userId()))
                .orElse(List.of());
    }

    /**
     * 群内下一次交互时的惰性归档结算（ET-39，不引入调度器）——
     * 群静默期满后有消息到达即公告归档并退群；到期未交互的群保持现状直到有人说话。
     *
     * <p><b>归档成立时不产生任何「群内回执」</b>：公告已发到群里（成员看得到），随即
     * {@code tradeGroupPort.leave} 退群——再往该群发文本必然 403。故本方法只回报「已归档」
     * 这一个事实，由调用方据此跳过本条消息的处理。
     *
     * @return {@code true} = 本次完成归档（公告已发、bot 已退群；调用方应跳过该消息处理）；
     *         {@code false} = 无动作（无绑定 / 未到期 / 非静默态 / 群动作失败）
     */
    public boolean reapIfDue(long chatId) {
        return tradeGroupSideEffects.reapIfDue(chatId);
    }

    /** 单步迁移：把订单交给服务层方法（本层不解释其返回值）。 */
    @FunctionalInterface
    private interface OrderStep {
        Object run(EscrowOrder order);
    }

    /** 通知未送达时追加给发起方的那一句；送达则什么都不加（不误报）。 */
    private static String unreachableHint(NotificationOutcome outcome) {
        return outcome == NotificationOutcome.RECIPIENT_UNREACHABLE
                ? "\n" + UNREACHABLE_HINT : "";
    }

    /** 动作 → 成功回执措辞（不把服务层返回值当展示形态）。 */
    private static String actionSucceeded(String action) {
        return switch (action) {
            case "托管" -> "进入「已锁仓（流程登记）」";
            case "交付" -> "标记为「已交付（流程登记）」";
            case "验收放款" -> "放款给卖方（流程登记）";
            case "退款" -> "退款（流程登记）";
            case "争议" -> "进入「争议中」，等待裁决";
            default -> action + "（完成）";
        };
    }

    /**
     * 取消订单：查单 → 交服务层做权限与状态校验 → 落库。
     *
     * <p>权限与状态守卫都在服务层（{@link EscrowTradeService#cancel}）——命令层只把
     * 「订单不存在」「无权」「状态不允许」翻译成用户能懂的文案，不做第二套判定。
     */
    private String handleCancel(BotCommand cmd, CommandActor actor) {
        Long orderId = CommandParsing.parseLong(cmd.argOpt(1).orElse(null));
        if (orderId == null) {
            return quickUsage(SUB_CANCEL, "<订单号>");
        }
        EscrowOrder order = lookup.byId(orderId).orElse(null);
        if (order == null) {
            return "订单 #" + orderId + " 不存在（请核对订单号）";
        }
        try {
            service.cancel(order, actor.userId());
        } catch (ConcurrentOrderUpdateException ex) {
            // 并发冲突与"无权限/状态不符"是两回事：前者重查后重试有意义，不能笼统说"无法取消"
            return "订单 #" + orderId + " 已被他人变更，请重查后再操作";
        } catch (EscrowException ex) {
            return "无法取消：" + ex.getMessage();
        }
        NotificationOutcome outcome = notifier.notify(order, actor.userId(), TradeEvent.CANCELLED);
        return "订单 #" + orderId + " 已取消"
                + tradeGroupSideEffects.settleGroupIfNeeded(order, TradeEvent.CANCELLED)
                + unreachableHint(outcome);
    }
}
