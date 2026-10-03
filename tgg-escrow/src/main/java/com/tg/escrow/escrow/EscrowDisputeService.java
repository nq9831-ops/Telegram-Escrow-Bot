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

import com.tg.escrow.common.EscrowException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * 争议会话编排（ET-60 双方陈述 + ET-45 证据窗口）——把两个早建成却零调用的件接到争议流程上。
 *
 * <h2>它补的是什么缺口</h2>
 * <p>{@link DisputeStatementFlow}（"双方各一条完整陈述 + 互认已读"）与 {@link EvidenceDeadline}
 * （"争议发起后固定窗口内举证，不可暂停"）此前都是<b>无人调用的孤岛</b>——争议流程只有
 * "发起 + 一句理由"。本服务把两者接进用户可触达的路径，并让会话经 {@link DisputeSessionStore}
 * 落库（重启不丢）。
 *
 * <h2>规则只在一处定义</h2>
 * <p>「每方限一条陈述」「只能读对方」「双陈述齐 + 互认已读才完成」三条规则<b>只</b>在
 * {@link DisputeStatementFlow}；「窗口从发起时刻起算、不可暂停」只在 {@link EvidenceDeadline}。
 * 本服务只做<b>恢复—推进—回写</b>（同 {@code TradeGroupService} 对状态机的取向），不复制规则。
 *
 * <h2>失败方向</h2>
 * <p>非争议态、非当事人、空内容、超长内容一律抛 {@link EscrowException}（fail-closed），
 * 由命令层转成可读回执。
 *
 * <p>时钟由构造注入，使窗口判定可测、且同一路径内时刻一致。
 */
public final class EscrowDisputeService {

    /** 陈述文本上限——与 {@code escrow_disputes} 列宽一致，超长在服务层拦下而非靠 DB 报错。 */
    public static final int MAX_STATEMENT_LENGTH = 2000;

    private final DisputeSessionStore store;
    private final Duration evidenceWindow;
    private final Clock clock;

    public EscrowDisputeService(DisputeSessionStore store, Duration evidenceWindow, Clock clock) {
        if (store == null) {
            throw new EscrowException("争议会话：未提供存储");
        }
        if (evidenceWindow == null || evidenceWindow.isZero() || evidenceWindow.isNegative()) {
            throw new EscrowException("争议会话：证据窗口必须为正，实为 " + evidenceWindow);
        }
        if (clock == null) {
            throw new EscrowException("争议会话：未提供时钟");
        }
        this.store = store;
        this.evidenceWindow = evidenceWindow;
        this.clock = clock;
    }

    /**
     * 争议发起后开启会话（幂等：已有则原样返回）。
     *
     * <p>由争议发起的调用点在迁移成功后立即调用——证据窗口的起点因此≈争议发起时刻。
     * 若因故未开成，后续任一读写会经 {@link #ensureSession} 惰性补齐（起点取补齐时刻）。
     *
     * @param order 已进入 {@code DISPUTED} 的订单
     * @throws EscrowException 订单非 {@code DISPUTED}、或未落库
     */
    public DisputeSession open(EscrowOrder order) {
        DisputeFlow.requireDisputed(order, "建立争议会话");
        return ensureSession(order);
    }

    /** 查会话（缺失时惰性建立）——供命令层渲染状态。 */
    public DisputeSession sessionOf(EscrowOrder order) {
        return ensureSession(order);
    }

    /**
     * 提交己方陈述（每方限一条）。
     *
     * @throws EscrowException 订单非争议态、提交人非买卖双方、内容空白/超长、或该方已陈述过
     */
    public DisputeSession submitStatement(EscrowOrder order, long actorId, String text) {
        DisputeFlow.requireDisputed(order, "提交争议陈述");
        if (text == null || text.isBlank()) {
            throw new EscrowException("争议陈述：内容不得空白");
        }
        if (text.length() > MAX_STATEMENT_LENGTH) {
            throw new EscrowException("争议陈述：内容过长（上限 " + MAX_STATEMENT_LENGTH
                    + " 字，实为 " + text.length() + " 字）");
        }
        long buyerId = order.getBuyerUserId();
        long sellerId = order.getSellerUserId();
        DisputeSession session = ensureSession(order);
        DisputeStatementFlow flow = session.toFlow(buyerId, sellerId);
        flow.submit(actorId, text);
        session.applyFlow(flow, buyerId, sellerId, clock.instant());
        return store.save(session);
    }

    /**
     * 确认已读<b>对方</b>的陈述（对方尚未陈述时拒绝）。
     *
     * @throws EscrowException 订单非争议态、确认人非买卖双方、读自己、或对方尚未陈述
     */
    public DisputeSession markRead(EscrowOrder order, long readerId) {
        DisputeFlow.requireDisputed(order, "确认已读");
        long buyerId = order.getBuyerUserId();
        long sellerId = order.getSellerUserId();
        long authorId = readerId == buyerId ? sellerId : buyerId;
        DisputeSession session = ensureSession(order);
        DisputeStatementFlow flow = session.toFlow(buyerId, sellerId);
        flow.markRead(readerId, authorId);
        session.applyFlow(flow, buyerId, sellerId, clock.instant());
        return store.save(session);
    }

    /** 陈述阶段是否完成（双陈述齐 + 互认已读）——供裁决前置门读取。 */
    public boolean statementsComplete(EscrowOrder order) {
        EscrowOrder o = requireOrder(order);
        return ensureSession(o).toFlow(o.getBuyerUserId(), o.getSellerUserId()).complete();
    }

    /**
     * 裁决前置门（ET-60 的兑现点）：陈述阶段未完成即拒。
     *
     * @throws EscrowException 陈述阶段未完成
     */
    public void requireStatementsComplete(EscrowOrder order) {
        if (!statementsComplete(order)) {
            throw new EscrowException("裁决流程：争议双方尚未完成陈述"
                    + "（须双方各一条完整陈述且互认已读）——如本部署不要求陈述前置，"
                    + "请关闭 tgg.dispute.require-statements");
        }
    }

    /**
     * 证据窗口（ET-45）——起点为会话发起时刻，不可暂停。
     *
     * @throws EscrowException 订单为 {@code null} 或未落库
     */
    public EvidenceDeadline evidenceDeadline(EscrowOrder order) {
        DisputeSession session = ensureSession(requireOrder(order));
        return new EvidenceDeadline(session.getOpenedAt(), evidenceWindow);
    }

    /** 证据窗口是否已过（{@code now >= deadline}）——过期即视为放弃举证。 */
    public boolean evidenceExpired(EscrowOrder order, Instant now) {
        return evidenceDeadline(order).isExpired(now);
    }

    /**
     * 记录一份争议证据（ET-43 双通道 + ET-45 窗口门）。
     *
     * <p>窗口校验在记录<b>之前</b>：过期即拒——不得先记录再报错，那会把过期证据也留下。
     * 两条通道的成功/失败由 {@link EvidenceChannel.Report} 各自上报，本方法不吞不改。
     *
     * @param channel 双通道（群内留痕 + 链上存证），由装配层按部署形态提供
     * @throws EscrowException 非争议态 / 非当事人 / 内容空白或超长 / 窗口已过
     */
    public EvidenceChannel.Report recordEvidence(EscrowOrder order, long actorId, String content,
                                                  EvidenceChannel channel) {
        DisputeFlow.requireDisputed(order, "提交争议证据");
        if (actorId != order.getBuyerUserId() && actorId != order.getSellerUserId()) {
            throw new EscrowException("争议证据：仅买卖双方可提交");
        }
        if (channel == null) {
            throw new EscrowException("争议证据：未提供双通道（上链可选须用空实现显式声明）");
        }
        if (content == null || content.isBlank()) {
            throw new EscrowException("争议证据：内容不得空白");
        }
        if (content.length() > MAX_STATEMENT_LENGTH) {
            throw new EscrowException("争议证据：内容过长（上限 " + MAX_STATEMENT_LENGTH
                    + " 字，实为 " + content.length() + " 字）");
        }
        EvidenceDeadline deadline = evidenceDeadline(order);
        if (deadline.isExpired(clock.instant())) {
            throw new EscrowException("争议证据：举证窗口已过（截止 " + deadline.deadlineAt()
                    + "）——过期视为放弃举证");
        }
        return channel.record(content);
    }

    private DisputeSession ensureSession(EscrowOrder order) {
        EscrowOrder o = requireOrder(order);
        Long orderId = o.getId();
        if (orderId == null) {
            throw new EscrowException("争议会话：订单尚未落库（无订单号），无法建立会话");
        }
        return store.find(orderId).orElseGet(() -> {
            // 起点取「争议进入时刻」而非当前时刻：DISPUTED 订单的 updatedAt 即 markDisputed 的时刻
            // （争议态不会再被别的迁移 touch）。若回退到 clock.instant()，惰性补齐的会话会把举证
            // 窗口整体后移——等于凭"会话曾没建成"多发一段举证期，是静默多给权利（已由用例钉住）。
            Instant start = o.currentState() == EscrowOrder.State.DISPUTED && o.getUpdatedAt() != null
                    ? o.getUpdatedAt()
                    : clock.instant();
            return store.save(new DisputeSession(orderId, start, start.plus(evidenceWindow)));
        });
    }

    private static EscrowOrder requireOrder(EscrowOrder order) {
        if (order == null) {
            throw new EscrowException("争议会话：未提供订单");
        }
        return order;
    }
}
