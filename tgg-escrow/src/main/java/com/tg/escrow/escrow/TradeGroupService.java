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
import java.util.Optional;
import java.util.Set;

/**
 * 交易群服务（ET-05/06/39/40）——把 {@link TradeGroupLifecycle} 的状态机接到真实事件上，
 * 并让每次推进经 {@link TradeGroupStore} 落库（重启不丢状态）。
 *
 * <h2>事件映射（语义边界在这里，不在调用方）</h2>
 * <pre>
 * 落单成功 + 群已建（ET-05/06）  ──onTradeCreated──▶ PENDING → LINKED → RECORDING（绑定 chatId）
 * 订单进入终态（ET-40）           ──onTradeSettled──▶ RECORDING → SILENT（记静默起点）
 * 静默期内群内再起争议           ──onDisputeInSilence──▶ SILENT → RECORDING（恢复留痕）
 * 群内交互 / 命令触发（ET-39）   ──onArchiveDue────▶ SILENT → ARCHIVED（惰性，到期才动）
 * </pre>
 *
 * <h2>为什么 onTradeSettled 只吃终态、onDisputeInSilence 只吃静默态</h2>
 * <p>{@code TradeGroupLifecycle.disputeOpened} 表达的是「<b>静默期内</b>再起争议」，与订单自身的
 * {@code DISPUTED}（争议中，仍可走向放款/退款）<b>不是同一事件</b>。若把「订单刚进入 DISPUTED」
 * 就喂给 {@code disputeOpened}，两处状态机会互相打架。故：
 * <ul>
 *   <li>{@code DISPUTED} 不在终态集里——{@link #onTradeSettled} 对它直接拒绝，群不会因此移动；</li>
 *   <li>{@link #onDisputeInSilence} 只接受处于 {@code SILENT} 的群，其余态由状态机 fail-closed。</li>
 * </ul>
 *
 * <h2>失败方向一律指向「不阻断交易」</h2>
 * <p>群是交易的<b>辅助载体</b>：建群可能因无权限/未配置而失败。此时没有绑定行，结算/归档/争议
 * 对群一律是<b>空操作</b>（返回空 {@link Optional}），绝不因"群没建起来"而回滚或阻断交易主流程
 * ——与 {@code TradeNotifier} 通知失败不回滚迁移同一取向。
 *
 * <p>时钟由构造注入，使归档/静默判定可测、且同一路径内时刻一致。
 */
public final class TradeGroupService {

    /** 订单终态——进入后群才可进静默期。{@code DISPUTED} <b>刻意不在内</b>（争议非终态）。 */
    private static final Set<EscrowOrder.State> TERMINAL_STATES = Set.of(
            EscrowOrder.State.RELEASED, EscrowOrder.State.REFUNDED, EscrowOrder.State.CANCELLED);

    private final TradeGroupStore store;
    private final Duration silence;
    private final Clock clock;

    public TradeGroupService(TradeGroupStore store, Duration silence, Clock clock) {
        if (store == null) {
            throw new EscrowException("交易群服务：未提供存储");
        }
        if (silence == null || silence.isZero() || silence.isNegative()) {
            throw new EscrowException("交易群服务：静默期时长必须为正，实为 " + silence);
        }
        if (clock == null) {
            throw new EscrowException("交易群服务：未提供时钟");
        }
        this.store = store;
        this.silence = silence;
        this.clock = clock;
    }

    /**
     * 落单成功、群已建好并邀请完成：建立绑定并把群置为留痕中。
     *
     * <p>建群能力未配置或 Telegram 调用失败时，调用方<b>不应</b>调本方法（没有 chatId 可绑），
     * 而应在回执里如实说明"群未建"——真机建群不在本层。
     *
     * @param order  已落库的订单（交易号取 {@link EscrowOrder#getId()}）
     * @param chatId 已建成的群 chat ID
     * @return 已保存的群绑定（状态 {@code RECORDING}）
     * @throws EscrowException 订单为 {@code null}、尚未落库（无交易号），或该交易已有群绑定
     */
    public TradeGroup onTradeCreated(EscrowOrder order, long chatId) {
        Instant now = clock.instant();
        long tradeId = requireTradeId(order);
        if (store.find(tradeId).isPresent()) {
            throw new EscrowException("交易群：交易 " + tradeId + " 已有交易群（每笔新交易新建群、不复用）");
        }
        TradeGroup group = new TradeGroup(tradeId, chatId, now);
        TradeGroupLifecycle lifecycle = group.toLifecycle(silence);
        lifecycle.link(now);
        lifecycle.startRecording(now);
        group.applyLifecycle(lifecycle, now);
        return store.save(group);
    }

    /**
     * 订单进入终态 → 群进入静默期。
     *
     * @param order 订单（其 {@link EscrowOrder#currentState()} 必须为终态）
     * @return 已保存的群绑定；<b>群未建时为 {@link Optional#empty()}</b>（降级不阻断）
     * @throws EscrowException 订单为 {@code null}/未落库，或订单<b>非终态</b>（含 {@code DISPUTED}）
     */
    public Optional<TradeGroup> onTradeSettled(EscrowOrder order) {
        Instant now = clock.instant();
        long tradeId = requireTradeId(order);
        EscrowOrder.State state = order.currentState();
        if (!TERMINAL_STATES.contains(state)) {
            throw new EscrowException("交易群：订单 " + tradeId + " 当前 " + state
                    + "，非终态不得进入静默期（争议不是终态——群 disputeOpened 是「静默期内再争议」，"
                    + "与订单 DISPUTED 不是同一事件）");
        }
        return store.find(tradeId).map(group -> {
            TradeGroupLifecycle lifecycle = group.toLifecycle(silence);
            lifecycle.enterSilence(now);
            group.applyLifecycle(lifecycle, now);
            return store.save(group);
        });
    }

    /**
     * 静默期内群内再起争议 → 恢复留痕（{@code SILENT} → {@code RECORDING}）。
     *
     * @param tradeId 交易号
     * @return 已保存的群绑定；群未建时为 {@link Optional#empty()}
     * @throws EscrowException 群存在但不在静默期（{@code disputeOpened} 只从 {@code SILENT} 发起）
     */
    public Optional<TradeGroup> onDisputeInSilence(long tradeId) {
        Instant now = clock.instant();
        return store.find(tradeId).map(group -> {
            TradeGroupLifecycle lifecycle = group.toLifecycle(silence);
            lifecycle.disputeOpened(now);
            group.applyLifecycle(lifecycle, now);
            return store.save(group);
        });
    }

    /**
     * 惰性归档结算（ET-39）：静默期满则归档，否则<b>什么都不做</b>。
     *
     * <p>项目不引入调度器——归档由"群内下一次交互/命令触发"时结算，故本方法是幂等的：
     * 未到期、非静默态、已归档、群未建，一律返回空。
     *
     * @param tradeId 交易号
     * @return 归档后的群绑定；无需归档时为 {@link Optional#empty()}
     */
    public Optional<TradeGroup> onArchiveDue(long tradeId) {
        Instant now = clock.instant();
        return store.find(tradeId).flatMap(group -> {
            TradeGroupLifecycle lifecycle = group.toLifecycle(silence);
            if (lifecycle.currentState() != TradeGroupLifecycle.State.SILENT
                    || !lifecycle.isArchiveDue(now)) {
                return Optional.empty();
            }
            lifecycle.archive(now);
            group.applyLifecycle(lifecycle, now);
            return Optional.of(store.save(group));
        });
    }

    /** 按交易号查群绑定（供命令层在回执里带群信息；群未建时为空）。 */
    public Optional<TradeGroup> find(long tradeId) {
        return store.find(tradeId);
    }

    /**
     * 群内消息到达时的惰性结算入口（ET-39，7 天归档）——不引入调度器：
     * 归档在<b>下一次群内交互</b>时判定并结算，到期未交互的群保持静默直到有人说话。
     *
     * <p>返回语义：{@code present} = 归档成立（本次刚归档、或此前已归档——幂等返回当前态）；
     * {@code empty} = 本次无归档动作（未绑定 / 留痕中 / 静默未到期）。
     *
     * @param chatId 群 chat ID
     * @return 归档成立时的群绑定
     */
    public Optional<TradeGroup> onGroupMessage(long chatId) {
        Instant now = clock.instant();
        return store.findByChatId(chatId).flatMap(group -> {
            TradeGroupLifecycle lifecycle = group.toLifecycle(silence);
            if (lifecycle.currentState() == TradeGroupLifecycle.State.ARCHIVED) {
                return Optional.of(group);
            }
            if (lifecycle.currentState() != TradeGroupLifecycle.State.SILENT
                    || !lifecycle.isArchiveDue(now)) {
                return Optional.empty();
            }
            lifecycle.archive(now);
            group.applyLifecycle(lifecycle, now);
            return Optional.of(store.save(group));
        });
    }

    /**
     * 取交易号：{@code EscrowOrder.getId()} 由落单回填、类型可空，构造 lifecycle 前必须判空，
     * 否则自动拆箱 NPE——且会把一笔未落库的订单按 0 号交易建群，绑定关系全错。
     */
    private static long requireTradeId(EscrowOrder order) {
        if (order == null) {
            throw new EscrowException("交易群：未提供订单");
        }
        Long id = order.getId();
        if (id == null) {
            throw new EscrowException("交易群：订单尚未落库（无交易号），无法绑定交易群——"
                    + "落单回填失败时不得按 0 号交易建群");
        }
        return id;
    }
}
