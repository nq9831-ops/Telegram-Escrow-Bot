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
import com.tg.escrow.core.BotCommand;
import com.tg.escrow.core.CommandActor;
import com.tg.escrow.core.TradeGroupException;
import com.tg.escrow.core.TradeGroupPort;
import com.tg.escrow.escrow.DisputeSession;
import com.tg.escrow.escrow.EscrowDisputeService;
import com.tg.escrow.escrow.EscrowOrder;
import com.tg.escrow.escrow.EscrowOrderLookupPort;
import com.tg.escrow.escrow.EvidenceChannel;
import com.tg.escrow.escrow.TradeGroupService;
import com.tg.escrow.escrow.TradeReviewService;

/**
 * 争议域命令处理器——从 {@link TradeCommandHandler} 拆出（2026-10-03 拆分时方法体逐字保留）。
 *
 * <p>覆盖子命令：{@code dispute} 的会话建立之外的部分 / {@code statement} / {@code read} /
 * {@code evidence} / {@code review}。守卫全部在域服务层（{@link EscrowDisputeService} /
 * {@link TradeReviewService}），本类只解析参数、装配双通道并如实转述结果。
 */
final class DisputeCommands {

    private final EscrowOrderLookupPort lookup;
    private final EscrowDisputeService disputeService;
    private final TradeGroupService tradeGroupService;
    private final TradeGroupPort tradeGroupPort;
    private final TradeReviewService reviewService;
    /** 链上存证腿（ET-43）；{@code null} = 未接线（记录时该腿抛错 → 如实报「未成功」）。 */
    private final ChainEvidenceSink evidenceChainSink;

    DisputeCommands(EscrowOrderLookupPort lookup,
                    EscrowDisputeService disputeService,
                    TradeGroupService tradeGroupService,
                    TradeGroupPort tradeGroupPort,
                    TradeReviewService reviewService,
                    ChainEvidenceSink evidenceChainSink) {
        this.lookup = lookup;
        this.disputeService = disputeService;
        this.tradeGroupService = tradeGroupService;
        this.tradeGroupPort = tradeGroupPort;
        this.reviewService = reviewService;
        this.evidenceChainSink = evidenceChainSink;
    }

    /**
     * 提交争议陈述（ET-60）。
     *
     * <p>陈述文本取第 3 个参数起<b>剩余全部</b>（按空白切分后拼回）——自由文本含空格很正常，
     * 不该逼用户去记转义规则。守卫（争议态、当事人、每方一条、非空、长度）全在
     * {@link EscrowDisputeService} 与 {@code DisputeStatementFlow}，本方法只解析与转述。
     */
    String handleStatement(BotCommand cmd, CommandActor actor) {
        Long orderId = parseLong(cmd.argOpt(1).orElse(null));
        String text = joinArgsFrom(cmd, 2);
        if (orderId == null || text.isBlank()) {
            return TradeCommandHandler.quickUsage("statement", "<订单号> <陈述>");
        }
        EscrowOrder order = lookup.byId(orderId).orElse(null);
        if (order == null) {
            return "订单 #" + orderId + " 不存在（请核对订单号）";
        }
        DisputeSession session;
        try {
            session = disputeService.submitStatement(order, actor.userId(), text);
        } catch (EscrowException ex) {
            return "无法提交陈述：" + ex.getMessage();
        }
        return "已记录你的争议陈述（订单 #" + orderId + "）。\n" + statementProgress(session)
                + "\n对方陈述后请发 /escrow read " + orderId + " 确认已读。";
    }

    /**
     * 确认已读对方陈述（ET-60）——回执先复述对方陈述（确认读的就是它），再落「已读」。
     * 对方尚未陈述时拒绝（规则在 {@code DisputeStatementFlow}）。
     */
    String handleRead(BotCommand cmd, CommandActor actor) {
        Long orderId = parseLong(cmd.argOpt(1).orElse(null));
        if (orderId == null) {
            return TradeCommandHandler.quickUsage("read", "<订单号>");
        }
        EscrowOrder order = lookup.byId(orderId).orElse(null);
        if (order == null) {
            return "订单 #" + orderId + " 不存在（请核对订单号）";
        }
        DisputeSession session;
        try {
            session = disputeService.markRead(order, actor.userId());
        } catch (EscrowException ex) {
            return "无法确认已读：" + ex.getMessage();
        }
        return "你已确认已读对方的争议陈述（订单 #" + orderId + "）。\n"
                + "对方陈述：" + opponentStatement(session, order, actor.userId()) + "\n"
                + statementProgress(session);
    }

    /**
     * 提交争议证据（ET-43 双通道 + ET-45 窗口门）。
     *
     * <p>守卫（争议态、当事人、窗口未过、内容非空/不超长）全在 {@link EscrowDisputeService}；
     * 本方法只解析、装配双通道并如实转述两条腿的结果。
     */
    String handleEvidence(BotCommand cmd, CommandActor actor) {
        Long orderId = parseLong(cmd.argOpt(1).orElse(null));
        String content = joinArgsFrom(cmd, 2);
        if (orderId == null || content.isBlank()) {
            return TradeCommandHandler.quickUsage("evidence", "<订单号> <证据>");
        }
        EscrowOrder order = lookup.byId(orderId).orElse(null);
        if (order == null) {
            return "订单 #" + orderId + " 不存在（请核对订单号）";
        }
        EvidenceChannel.Report report;
        try {
            report = disputeService.recordEvidence(order, actor.userId(), content,
                    evidenceChannelFor(orderId));
        } catch (EscrowException ex) {
            return "无法提交证据：" + ex.getMessage();
        }
        return "已记录争议证据（订单 #" + orderId + "）。\n" + evidenceChannels(report);
    }

    /**
     * 交易评价：命令层只负责解析参数与转述，守卫全在 {@link TradeReviewService} / {@code TradeReview}。
     */
    String handleReview(BotCommand cmd, CommandActor actor) {
        Long orderId = parseLong(cmd.argOpt(1).orElse(null));
        Integer score = parseInt(cmd.argOpt(2).orElse(null));
        if (orderId == null || score == null) {
            return TradeCommandHandler.quickUsage("review", "<订单号> <评分1-5>");
        }
        EscrowOrder order = lookup.byId(orderId).orElse(null);
        if (order == null) {
            return "订单 #" + orderId + " 不存在（请核对订单号）";
        }
        try {
            reviewService.review(order, actor.userId(), score);
        } catch (EscrowException ex) {
            return "无法评价：" + ex.getMessage();
        }
        return "已记录你对订单 #" + orderId + " 的评价（" + score + " 分）。";
    }

    /** 陈述进度的一句话（买方/卖方各自是否已陈述、是否互认已读）——供回执复用。 */
    private static String statementProgress(DisputeSession session) {
        boolean bothRead = session.getBuyerReadAt() != null && session.getSellerReadAt() != null;
        return "陈述进度：买方" + (session.getBuyerStatement() != null ? "已陈述" : "未陈述")
                + "、卖方" + (session.getSellerStatement() != null ? "已陈述" : "未陈述")
                + "；互认已读：" + (bothRead ? "已完成" : "未完成");
    }

    /** 读取者<b>对方</b>的陈述文本（调用于 {@code markRead} 成功之后，故非空）。 */
    private static String opponentStatement(DisputeSession session, EscrowOrder order, long readerId) {
        return readerId == order.getBuyerUserId()
                ? session.getSellerStatement()
                : session.getBuyerStatement();
    }

    /** 双通道状态的一句话——如实区分「未成功」与「未接入」，不静默。 */
    private static String evidenceChannels(EvidenceChannel.Report report) {
        return "群内留痕：" + (report.groupLogSaved() ? "已留存" : "未成功")
                + "；链上存证：" + (report.chainSaved() ? "已上链" : "未成功")
                + (report.complete() ? "" : "\n⚠️ 证据未双通道保全，请核对后重提或另行留存。");
    }

    /**
     * 按订单装配证据双通道：群内腿 = 投递到该订单绑定的交易群；链上腿 = {@link ChainEvidenceSink}。
     *
     * <p><b>失败即抛</b>（由 {@link EvidenceChannel#record} 捕获转 {@code chainSaved=false}）：
     * 「未部署链上合约」「链上发送失败」「存证未接线」都如实回落成"未成功"，
     * 由回执披露。<b>刻意不做"不抛的空实现"</b>——空实现会被 trySave 当作成功，
     * 把"未上链"显示成"已上链"。
     */
    private EvidenceChannel evidenceChannelFor(long orderId) {
        return new EvidenceChannel(
                text -> tradeGroupService.find(orderId)
                        .map(group -> {
                            tradeGroupPort.announce(group.getChatId(),
                                    "📎 订单 #" + orderId + " 争议证据：\n" + text);
                            return group;
                        })
                        .orElseThrow(() -> new TradeGroupException(
                                "该订单未绑定交易群，群内留痕腿不可用")),
                text -> {
                    if (evidenceChainSink == null) {
                        throw new EscrowException("链上存证未接线（缺少 S5 部署装配）");
                    }
                    evidenceChainSink.save(orderId, text);
                });
    }

    /**
     * 取第 {@code from} 个参数起的剩余文本（按单个空格拼回）。
     *
     * <p>命令解析按空白切分参数，自由文本（陈述/证据）里的空格因此会变成多个参数——
     * 必须拼回，否则「我先付款 他没发货」会被截成「我先付款」。
     */
    private static String joinArgsFrom(BotCommand cmd, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < cmd.argCount(); i++) {
            String part = cmd.argOpt(i).orElse("");
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(part);
        }
        return sb.toString();
    }

    static Long parseLong(String s) {
        return CommandParsing.parseLong(s);
    }

    static Integer parseInt(String s) {
        return CommandParsing.parseInt(s);
    }
}
