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
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 争议会话编排（ET-60/45）的用户级行为：陈述 → 互认已读 → 完成，以及证据窗口的闭区间到期。
 *
 * <p>期望值全部手算/逐条钉：陈述完成条件是「双陈述齐 + 互认已读」（缺一不可）、
 * 证据窗口到点即过期（{@code now >= deadline}）。
 */
class EscrowDisputeServiceTest {

    private static final long BUYER = 11L;
    private static final long SELLER = 22L;
    private static final long STRANGER = 99L;
    private static final Instant T0 = Instant.parse("2026-10-02T00:00:00Z");
    private static final Duration WINDOW = Duration.ofHours(24);

    private static Clock clockAt(Instant t) {
        return Clock.fixed(t, ZoneOffset.UTC);
    }

    /** 一笔处于 DISPUTED 的订单（买方/卖方固定，构造期即可指定订单号）。 */
    private static EscrowOrder disputedOrder(long id) {
        EscrowOrder order = new EscrowOrder(BUYER, SELLER, new BigDecimal("10"), "TON", T0);
        order.assignId(id);
        order.markLocked(T0);
        order.markDelivered(T0);
        order.markDisputed("货不对板", T0);
        return order;
    }

    private static EscrowDisputeService service(DisputeSessionStore store, Instant now) {
        return new EscrowDisputeService(store, WINDOW, clockAt(now));
    }

    @Test
    void openCreatesSessionWhoseDeadlineIsOpenedPlusWindow() {
        DisputeSession session = service(new MapStore(), T0).open(disputedOrder(1));

        assertEquals(1L, session.getOrderId());
        assertEquals(T0, session.getOpenedAt());
        assertEquals(T0.plus(WINDOW), session.getEvidenceDeadlineAt());
    }

    @Test
    void openIsIdempotentAndNeverMovesTheStart() {
        MapStore store = new MapStore();
        EscrowOrder order = disputedOrder(1);
        service(store, T0).open(order);

        // 五小时后再次 open：起点仍旧，证据窗口不因重复调用而漂移
        DisputeSession again = service(store, T0.plus(Duration.ofHours(5))).open(order);

        assertEquals(T0, again.getOpenedAt());
        assertEquals(T0.plus(WINDOW), again.getEvidenceDeadlineAt());
    }

    @Test
    void nonDisputedOrderCannotOpenSession() {
        EscrowOrder open = new EscrowOrder(BUYER, SELLER, new BigDecimal("10"), "TON", T0);
        open.assignId(2);

        assertThrows(EscrowException.class, () -> service(new MapStore(), T0).open(open));
    }

    @Test
    void completionNeedsBothStatementsAndMutualRead() {
        DisputeSessionStore store = new MapStore();
        EscrowOrder order = disputedOrder(3);

        assertFalse(service(store, T0).statementsComplete(order), "空会话不算完成");

        service(store, T0).submitStatement(order, BUYER, "我先付款了，他没发货");
        assertFalse(service(store, T0).statementsComplete(order), "仅一方陈述不算完成");

        // 对方尚未陈述 → 无从已读
        assertThrows(EscrowException.class, () -> service(store, T0).markRead(order, BUYER));

        service(store, T0).submitStatement(order, SELLER, "货已发出，物流签收");
        assertFalse(service(store, T0).statementsComplete(order), "双陈述齐但未互认已读，仍不算完成");

        service(store, T0).markRead(order, BUYER);
        service(store, T0).markRead(order, SELLER);
        assertTrue(service(store, T0).statementsComplete(order), "双陈述齐 + 互认已读 → 完成");
    }

    @Test
    void eachPartyIsLimitedToOneStatement() {
        EscrowDisputeService svc = service(new MapStore(), T0);
        EscrowOrder order = disputedOrder(4);

        svc.submitStatement(order, BUYER, "第一次陈述");
        assertThrows(EscrowException.class, () -> svc.submitStatement(order, BUYER, "再来一条"));
    }

    @Test
    void strangerCannotState() {
        EscrowDisputeService svc = service(new MapStore(), T0);
        EscrowOrder order = disputedOrder(5);

        assertThrows(EscrowException.class, () -> svc.submitStatement(order, STRANGER, "路人"));
    }

    @Test
    void blankAndOverlongStatementsAreRejected() {
        EscrowDisputeService svc = service(new MapStore(), T0);
        EscrowOrder order = disputedOrder(6);

        assertThrows(EscrowException.class, () -> svc.submitStatement(order, BUYER, "   "));
        String tooLong = "x".repeat(EscrowDisputeService.MAX_STATEMENT_LENGTH + 1);
        assertThrows(EscrowException.class, () -> svc.submitStatement(order, BUYER, tooLong));
    }

    @Test
    void requireStatementsCompleteFailsClosedWithReadableReason() {
        EscrowDisputeService svc = service(new MapStore(), T0);
        EscrowOrder order = disputedOrder(7);

        EscrowException ex = assertThrows(EscrowException.class, () -> svc.requireStatementsComplete(order));
        assertTrue(ex.getMessage().contains("陈述"), ex.getMessage());
    }

    @Test
    void evidenceWindowIsHalfOpenAtTheDeadline() {
        EscrowDisputeService svc = service(new MapStore(), T0);
        EscrowOrder order = disputedOrder(8);

        assertFalse(svc.evidenceExpired(order, T0.plus(Duration.ofHours(23))), "窗口内未过期");
        assertTrue(svc.evidenceExpired(order, T0.plus(WINDOW)), "闭区间：到点即过期");
    }

    @Test
    void sessionIsReadBackThroughTheStore() {
        MapStore store = new MapStore();
        EscrowOrder order = disputedOrder(9);

        service(store, T0).submitStatement(order, BUYER, "买方陈述");

        DisputeSession reloaded = service(store, T0).sessionOf(order);
        assertEquals("买方陈述", reloaded.getBuyerStatement());
        assertEquals(T0, reloaded.getOpenedAt());
    }

    @Test
    void evidenceWithinWindowIsRecordedToBothChannels() {
        EscrowDisputeService svc = service(new MapStore(), T0);
        EscrowOrder order = disputedOrder(10);
        List<String> group = new ArrayList<>();
        List<String> chain = new ArrayList<>();

        EvidenceChannel.Report report = svc.recordEvidence(order, BUYER, "聊天记录截图哈希 0xabc",
                new EvidenceChannel(group::add, chain::add));

        assertTrue(report.complete(), "窗口内提交 → 双通道全成");
        assertEquals(List.of("聊天记录截图哈希 0xabc"), group);
        assertEquals(List.of("聊天记录截图哈希 0xabc"), chain);
    }

    @Test
    void evidenceAtDeadlineIsRejectedAndLeavesNothingBehind() {
        // 恰在截止时刻提交（闭区间：>= deadline 即过期）
        EscrowDisputeService svc = service(new MapStore(), T0.plus(WINDOW));
        EscrowOrder order = disputedOrder(11);
        List<String> group = new ArrayList<>();

        assertThrows(EscrowException.class, () -> svc.recordEvidence(order, BUYER, "迟到的证据",
                new EvidenceChannel(group::add, text -> { })));
        assertTrue(group.isEmpty(), "过期即拒——不得把过期证据先记下再报错（那会留下它）");
    }

    @Test
    void strangerCannotSubmitEvidence() {
        EscrowDisputeService svc = service(new MapStore(), T0);
        EscrowOrder order = disputedOrder(12);

        assertThrows(EscrowException.class, () -> svc.recordEvidence(order, STRANGER, "路人证据",
                new EvidenceChannel(text -> { }, text -> { })));
    }

    @Test
    void oneLegFailureIsReportedPerLegAndNotSwallowed() {
        EscrowDisputeService svc = service(new MapStore(), T0);
        EscrowOrder order = disputedOrder(13);
        List<String> chain = new ArrayList<>();

        EvidenceChannel.Report report = svc.recordEvidence(order, SELLER, "物流单号 SF123",
                new EvidenceChannel(text -> {
                    throw new IllegalStateException("群通道不可用");
                }, chain::add));

        assertFalse(report.groupLogSaved(), "群腿失败须如实上报（不得吞）");
        assertTrue(report.chainSaved(), "链腿成功不被群腿失败牵连");
        assertFalse(report.complete(), "一腿失败 → 整体不算保全完成");
        assertEquals(List.of("物流单号 SF123"), chain);
    }

    /** 内存会话存储：同一实例往返（不做深拷贝），够验服务对端口的读写契约。 */
    private static final class MapStore implements DisputeSessionStore {

        private final Map<Long, DisputeSession> rows = new LinkedHashMap<>();

        @Override
        public Optional<DisputeSession> find(long orderId) {
            return Optional.ofNullable(rows.get(orderId));
        }

        @Override
        public DisputeSession save(DisputeSession session) {
            rows.put(session.getOrderId(), session);
            return session;
        }
    }
}
