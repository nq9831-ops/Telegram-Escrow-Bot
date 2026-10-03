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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 危险动作的<b>二次确认槽</b>：把 {@code release} / {@code refund} 这类资金动作从
 * 「一按就做」变成「先发起、再确认」。
 *
 * <h2>为什么需要它</h2>
 * <p>放款与退款是不可逆的资金语义动作，而按钮点击与命令发送都是单次输入——一次误触即执行。
 * 本槽位把「确认」变成可校验的事实：第 1 次输入只<b>登记</b>槽位并回确认提示，
 * 第 2 次输入必须命中同一槽位（同一订单、同一用户、同一动作、未过期）才放行。
 *
 * <h2>语义定死</h2>
 * <ul>
 *   <li><b>键 =（订单号, 用户 ID）</b>：语义是「某人对自己发起的动作做二次确认」——
 *       对方不可替你确认。<b>争议态下双方同权</b>（都能提放款/退款），此时买卖双方
 *       各持一个独立槽位，互不覆盖、互不干扰；</li>
 *   <li><b>身份 + 动作 + 时效</b>三者全中才放行：槽位记录的动作与本次请求的动作不同 → 不消费；</li>
 *   <li><b>一次性</b>：命中即消费，同一槽位不能确认两次；</li>
 *   <li><b>覆盖</b>：同一（订单号, 用户 ID）重复 offer 只留最近一次动作；</li>
 *   <li><b>过期</b>：超过 {@code createdAt + ttl} 即失效（边界：恰好在 TTL 上仍有效，
 *       严格晚于才过期——与 {@link PendingTradeRegistry} 同口径）。</li>
 * </ul>
 *
 * <h2>键空间上界与前提</h2>
 * <p>键空间由「存活中的 (订单, 用户) 对」界定，<b>随订单数增长但被 TTL 与主动清理封顶</b>：
 * {@link #offer} 时顺带清扫过期槽位，未确认的槽位不会永久驻留（否则长期运行内存单调上升）。
 * 本类型是<b>单节点进程内</b>结构（{@link ConcurrentHashMap}）——多实例部署下确认槽不共享，
 * 这与 {@code PendingTradeRegistry} 的既有前提一致。
 *
 * <p>线程安全：它是会被多 Update 并发的单例，不假设调用顺序。
 */
public final class ConfirmGate {

    /** 槽位键：同一订单下每个用户各持一个独立槽位（互不覆盖、互不干扰）。 */
    public record Key(long orderId, long userId) {
    }

    /** 一条待确认动作：动作标识 + 登记时刻。 */
    private record Slot(String action, Instant createdAt) {
    }

    private final Duration ttl;
    private final Clock clock;
    private final Map<Key, Slot> byKey = new ConcurrentHashMap<>();

    /**
     * @param ttl   确认窗口时长（正数）——配置键 {@code tgg.confirm.window}
     * @param clock 时钟（用于过期判定）
     */
    public ConfirmGate(Duration ttl, Clock clock) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new EscrowException("动作确认槽：有效期必须为正，实为 " + ttl);
        }
        if (clock == null) {
            throw new EscrowException("动作确认槽：时钟不可为空");
        }
        this.ttl = ttl;
        this.clock = clock;
    }

    /**
     * 登记（或覆盖）某人针对某订单的一个待确认动作。
     *
     * <p>顺带清扫过期槽位——把「清理」搭在本来就有的写路径上，不必引入调度器，
     * 也让键空间不随未确认动作单调增长。
     *
     * @param orderId 订单号（正）
     * @param userId  发起者（正）
     * @param action  动作标识（如 {@code rl} / {@code rf}）
     */
    public void offer(long orderId, long userId, String action) {
        if (orderId <= 0) {
            throw new EscrowException("动作确认槽：订单号必须为正，实为 " + orderId);
        }
        if (userId <= 0) {
            throw new EscrowException("动作确认槽：用户 ID 必须为正，实为 " + userId);
        }
        if (action == null || action.isBlank()) {
            throw new EscrowException("动作确认槽：动作标识不可为空");
        }
        purgeExpired();
        byKey.put(new Key(orderId, userId), new Slot(action, clock.instant()));
    }

    /**
     * 命中并消费一个待确认动作——<b>身份、动作、时效三者全中</b>才返回 {@code true}。
     *
     * @return {@code true} = 本次确认有效（槽位已被消费，可放行第 2 步）；
     *         {@code false} = 未发起 / 动作不符 / 已过期 / 已消费（须重新发起）
     */
    public boolean consume(long orderId, long userId, String action) {
        if (action == null || action.isBlank()) {
            return false;
        }
        // 「读出 → 判定 → 移除」必须是一个原子动作。compute 对同一 key 串行执行，而 get 之后
        // 再 remove 之间存在窗口：两次并发确认可以都读到同一条槽位、都判定通过，于是"一次性"被
        // 破坏，同一枚确认按钮能被点两次、放款执行两遍。这与 PendingTradeRegistry.consume 同一先例。
        AtomicReference<Slot> consumed = new AtomicReference<>();
        byKey.compute(new Key(orderId, userId), (key, slot) -> {
            if (slot == null) {
                return null;                        // 未发起
            }
            if (expired(slot)) {
                return null;                        // 过期：移除，需重新发起
            }
            if (!slot.action().equals(action)) {
                return slot;                        // 动作不符：保留原槽位（换回正确动作仍可确认）
            }
            consumed.set(slot);                     // 命中：本次消费掉
            return null;                            // 移除，保证一次性
        });
        return consumed.get() != null;
    }

    /** 当前存活槽位数量——供「过期后不增长」的内存上界断言使用。 */
    public int size() {
        return byKey.size();
    }

    /** 清扫已过期槽位（{@link #offer} 的写路径搭车调用）。 */
    private void purgeExpired() {
        for (Map.Entry<Key, Slot> entry : byKey.entrySet()) {
            if (expired(entry.getValue())) {
                byKey.remove(entry.getKey(), entry.getValue());
            }
        }
    }

    /** 过期判定：严格晚于 {@code createdAt + ttl} 才算过期（恰好到点仍有效）。 */
    private boolean expired(Slot slot) {
        return clock.instant().isAfter(slot.createdAt().plus(ttl));
    }
}
