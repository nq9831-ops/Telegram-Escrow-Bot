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
import com.tg.escrow.escrow.TradeGroupLifecycle.State;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 交易群服务（ET-05/06/39/40）的行为固定测试。
 *
 * <p>本类钉住三件事：
 * <ol>
 *   <li><b>语义边界</b>：{@link TradeGroupLifecycle#disputeOpened} 只表达「静默期内再起争议」，
 *       与订单自身的 {@code DISPUTED}（争议中，可走向放款/退款）<b>不是同一事件</b>——
 *       直连会让"订单刚争议"被误当成"静默期被争议终止"，故用显式用例挡住；</li>
 *   <li><b>tradeId 判空防护</b>：交易号由落单回填（{@code EscrowOrder.getId()} 可空），
 *       构造 lifecycle 前必须判空，否则自动拆箱 NPE；</li>
 *   <li><b>降级不阻断</b>：群是交易的辅助载体，未建群时结算/归档对群是空操作，绝不连累交易主流程。</li>
 * </ol>
 */
class TradeGroupServiceTest {

    private static final Instant T0 = Instant.parse("2026-09-23T10:00:00Z");
    private static final Duration SILENCE = Duration.ofDays(7);
    private static final long CHAT_ID = -1001234567890L;

    /** 内存存储替身：让服务层的编排可被穷举测试，不拖真库。 */
    private static final class InMemoryStore implements TradeGroupStore {
        final Map<Long, TradeGroup> rows = new LinkedHashMap<>();

        @Override
        public Optional<TradeGroup> find(long tradeId) {
            return Optional.ofNullable(rows.get(tradeId));
        }

        @Override
        public Optional<TradeGroup> findByChatId(long chatId) {
            return rows.values().stream()
                    .filter(g -> Long.valueOf(chatId).equals(g.getChatId()))
                    .findFirst();
        }

        @Override
        public TradeGroup save(TradeGroup group) {
            rows.put(group.getTradeId(), group);
            return group;
        }
    }

    /** 造一笔已落库（有 id）的订单。 */
    private static EscrowOrder order(long id) {
        EscrowOrder o = new EscrowOrder(1001L, 2002L, new BigDecimal("100"), "USDT", T0);
        o.assignId(id);
        return o;
    }

    private static EscrowOrder releasedOrder(long id) {
        EscrowOrder o = order(id);
        o.markLocked(T0);
        o.markDelivered(T0);
        o.markReleased(T0);
        return o;
    }

    private static EscrowOrder disputedOrder(long id) {
        EscrowOrder o = order(id);
        o.markLocked(T0);
        o.markDisputed("货不对板", T0);
        return o;
    }

    private static Clock at(Instant t) {
        return Clock.fixed(t, ZoneOffset.UTC);
    }

    private static TradeGroupService service(InMemoryStore store, Clock clock) {
        return new TradeGroupService(store, SILENCE, clock);
    }

    // ── 落单成功 → 建群绑定 ────────────────────────────────────────────

    @Test
    @DisplayName("落单成功 + 群已建：绑定交易号与 chatId，进入留痕中")
    void onTradeCreatedBindsTradeAndChat() {
        InMemoryStore store = new InMemoryStore();

        TradeGroup g = service(store, at(T0)).onTradeCreated(order(4242L), CHAT_ID);

        assertThat(g.getTradeId()).isEqualTo(4242L);
        assertThat(g.getChatId()).isEqualTo(CHAT_ID);
        assertThat(g.currentState()).isEqualTo(State.RECORDING);
        assertThat(store.find(4242L)).isPresent();
    }

    @Test
    @DisplayName("订单尚未落库（id 为空）→ 拒绝建群绑定，不得按 0 号交易建群（防拆箱 NPE）")
    void onTradeCreatedRejectsMissingTradeId() {
        InMemoryStore store = new InMemoryStore();
        EscrowOrder unsaved = new EscrowOrder(1001L, 2002L, new BigDecimal("100"), "USDT", T0);

        assertThatThrownBy(() -> service(store, at(T0)).onTradeCreated(unsaved, CHAT_ID))
                .isInstanceOf(EscrowException.class)
                .hasMessageContaining("交易号");
        assertThat(store.rows).isEmpty();
    }

    @Test
    @DisplayName("同一交易重复建群 → 拒绝（每笔新交易新建群、不复用）")
    void onTradeCreatedRejectsDuplicate() {
        InMemoryStore store = new InMemoryStore();
        TradeGroupService svc = service(store, at(T0));
        svc.onTradeCreated(order(4242L), CHAT_ID);

        assertThatThrownBy(() -> svc.onTradeCreated(order(4242L), -100999L))
                .isInstanceOf(EscrowException.class)
                .hasMessageContaining("已有交易群");
    }

    // ── 订单终态 → 静默期 ──────────────────────────────────────────────

    @Test
    @DisplayName("订单进入终态 → 群进入静默期，记静默起点")
    void onTradeSettledEntersSilence() {
        InMemoryStore store = new InMemoryStore();
        TradeGroupService svc = service(store, at(T0));
        svc.onTradeCreated(order(4242L), CHAT_ID);

        Optional<TradeGroup> settled = svc.onTradeSettled(releasedOrder(4242L));

        assertThat(settled).isPresent();
        assertThat(settled.get().currentState()).isEqualTo(State.SILENT);
        assertThat(settled.get().getSilenceStartedAt()).isEqualTo(T0);
    }

    @Test
    @DisplayName("语义边界：订单 DISPUTED 不是终态——不得驱动群进入静默 / disputeOpened")
    void disputedOrderDoesNotDriveGroup() {
        InMemoryStore store = new InMemoryStore();
        TradeGroupService svc = service(store, at(T0));
        svc.onTradeCreated(order(4242L), CHAT_ID);

        assertThatThrownBy(() -> svc.onTradeSettled(disputedOrder(4242L)))
                .isInstanceOf(EscrowException.class)
                .hasMessageContaining("终态");
        assertThat(store.find(4242L).orElseThrow().currentState())
                .as("群状态不得因订单争议而移动——群 disputeOpened 是「静默期内再争议」，与订单 DISPUTED 不是同一事件")
                .isEqualTo(State.RECORDING);
    }

    @Test
    @DisplayName("语义边界：群 disputeOpened 只在静默期内成立——留痕中触发即拒绝")
    void disputeOpenedRequiresSilence() {
        InMemoryStore store = new InMemoryStore();
        TradeGroupService svc = service(store, at(T0));
        svc.onTradeCreated(order(4242L), CHAT_ID);

        assertThatThrownBy(() -> svc.onDisputeInSilence(4242L))
                .isInstanceOf(EscrowException.class);
        assertThat(store.find(4242L).orElseThrow().currentState()).isEqualTo(State.RECORDING);
    }

    @Test
    @DisplayName("静默期内再起争议 → 群恢复留痕（silence 起点清空）")
    void disputeInSilenceReturnsToRecording() {
        InMemoryStore store = new InMemoryStore();
        TradeGroupService svc = service(store, at(T0));
        svc.onTradeCreated(order(4242L), CHAT_ID);
        svc.onTradeSettled(releasedOrder(4242L));

        Optional<TradeGroup> reopened = svc.onDisputeInSilence(4242L);

        assertThat(reopened).isPresent();
        assertThat(reopened.get().currentState()).isEqualTo(State.RECORDING);
        assertThat(reopened.get().getSilenceStartedAt()).isNull();
    }

    // ── 惰性归档 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("惰性归档：静默期未满 → 不归档、状态不变")
    void archiveNotDueDoesNothing() {
        InMemoryStore store = new InMemoryStore();
        TradeGroupService svc = service(store, at(T0));
        svc.onTradeCreated(order(4242L), CHAT_ID);
        svc.onTradeSettled(releasedOrder(4242L));

        assertThat(svc.onArchiveDue(4242L)).isEmpty();
        assertThat(store.find(4242L).orElseThrow().currentState()).isEqualTo(State.SILENT);
    }

    @Test
    @DisplayName("惰性归档：静默期满 → 归档终态，记归档时刻")
    void archiveWhenDue() {
        InMemoryStore store = new InMemoryStore();
        TradeGroupService settling = service(store, at(T0));
        settling.onTradeCreated(order(4242L), CHAT_ID);
        settling.onTradeSettled(releasedOrder(4242L));

        Instant later = T0.plus(Duration.ofDays(8));
        Optional<TradeGroup> archived = service(store, at(later)).onArchiveDue(4242L);

        assertThat(archived).isPresent();
        assertThat(archived.get().currentState()).isEqualTo(State.ARCHIVED);
        assertThat(archived.get().getArchivedAt()).isEqualTo(later);
    }

    // ── 降级 / 守卫 ────────────────────────────────────────────────────

    @Test
    @DisplayName("群未建（无绑定行）→ 结算/归档/争议对群都是空操作，不阻断交易主流程")
    void operationsWithoutGroupAreNoOps() {
        InMemoryStore store = new InMemoryStore();
        TradeGroupService svc = service(store, at(T0));

        assertThat(svc.onTradeSettled(releasedOrder(9999L))).isEmpty();
        assertThat(svc.onDisputeInSilence(9999L)).isEmpty();
        assertThat(svc.onArchiveDue(9999L)).isEmpty();
    }

    @Test
    @DisplayName("未提供订单 → 抛异常（不静默吞掉）")
    void nullOrderFailsClosed() {
        assertThatThrownBy(() -> service(new InMemoryStore(), at(T0)).onTradeSettled(null))
                .isInstanceOf(EscrowException.class);
    }

    @Test
    @DisplayName("缺依赖 / 非正静默期 → 构造即抛")
    void missingDependenciesFailFast() {
        assertThatThrownBy(() -> new TradeGroupService(null, SILENCE, at(T0)))
                .isInstanceOf(EscrowException.class);
        assertThatThrownBy(() -> new TradeGroupService(new InMemoryStore(), Duration.ZERO, at(T0)))
                .isInstanceOf(EscrowException.class);
        assertThatThrownBy(() -> new TradeGroupService(new InMemoryStore(), SILENCE, null))
                .isInstanceOf(EscrowException.class);
    }

    // ── 群内消息 → 惰性归档（ET-39；不引入调度器） ──────────────────────

    @Test
    @DisplayName("静默期满后群内消息 → 惰性归档（ET-39：下次交互时结算）")
    void groupMessageArchivesWhenDue() {
        InMemoryStore store = new InMemoryStore();
        TradeGroupService svc = service(store, at(T0));
        EscrowOrder o = releasedOrder(77L);
        svc.onTradeCreated(o, CHAT_ID);
        svc.onTradeSettled(o);
        assertThat(svc.find(77L)).isPresent()
                .get().extracting(TradeGroup::currentState).isEqualTo(TradeGroupLifecycle.State.SILENT);

        // 静默期（7 天）后的下一次群内交互触发结算
        TradeGroupService later = service(store, at(T0.plus(Duration.ofDays(7))));
        Optional<TradeGroup> settled = later.onGroupMessage(CHAT_ID);

        assertThat(settled).isPresent();
        assertThat(settled.get().currentState()).isEqualTo(TradeGroupLifecycle.State.ARCHIVED);
        assertThat(settled.get().getArchivedAt()).isEqualTo(T0.plus(Duration.ofDays(7)));
    }

    @Test
    @DisplayName("静默期未满 → 群内消息不归档（不提前收群）")
    void groupMessageBeforeDueDoesNotArchive() {
        InMemoryStore store = new InMemoryStore();
        TradeGroupService svc = service(store, at(T0));
        EscrowOrder o = releasedOrder(78L);
        svc.onTradeCreated(o, CHAT_ID);
        svc.onTradeSettled(o);

        TradeGroupService early = service(store, at(T0.plus(Duration.ofDays(3))));
        Optional<TradeGroup> result = early.onGroupMessage(CHAT_ID);

        assertThat(result).isEmpty();
        assertThat(early.find(78L)).isPresent()
                .get().extracting(TradeGroup::currentState).isEqualTo(TradeGroupLifecycle.State.SILENT);
    }

    @Test
    @DisplayName("无绑定 / 留痕中 / 已归档 → 群内消息是空操作（empty）")
    void groupMessageWithoutDueBindingIsNoop() {
        // 无绑定
        assertThat(service(new InMemoryStore(), at(T0)).onGroupMessage(CHAT_ID)).isEmpty();

        // 留痕中（未结算）
        InMemoryStore store = new InMemoryStore();
        TradeGroupService svc = service(store, at(T0));
        svc.onTradeCreated(releasedOrder(79L), CHAT_ID);
        assertThat(svc.onGroupMessage(CHAT_ID)).isEmpty();

        // 已归档（幂等，不重复动作）
        TradeGroupService later = service(store, at(T0.plus(Duration.ofDays(8))));
        svc.onTradeSettled(releasedOrder(79L));
        later.onGroupMessage(CHAT_ID);
        assertThat(later.onGroupMessage(CHAT_ID)).isPresent()
                .get().extracting(TradeGroup::currentState).isEqualTo(TradeGroupLifecycle.State.ARCHIVED);
    }
}
