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
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 待确认交易登记（ET-34 前置校验）：把 {@code create} 的风险提示变成 {@code confirm} 的必经前置。
 *
 * <h2>为什么需要它</h2>
 * <p>「创建交易前强制展示风险提示」若只存在于文案、而 {@code confirm} 可独立落单，
 * 提示就被架空——手滑直接 confirm 便跳过了与自己金额相关的那条提示。本登记把
 * 「看过提示」变成可校验的事实：{@code create} 记下请求，{@code confirm} 必须命中同一请求
 * （同买方、参数逐字一致、未过期）才放行。
 *
 * <h2>语义定死</h2>
 * <ul>
 *   <li><b>一次性</b>：命中即消费，同一登记不能 confirm 两次（防重复提交）；</li>
 *   <li><b>参数逐字一致</b>：金额/卖方/币种任一不同，即视为「没预览过这笔」，拒绝；</li>
 *   <li><b>按买方隔离</b>：A 的登记不能被 B 消费；</li>
 *   <li><b>覆盖</b>：同一买方重复 create 只留最近一次；</li>
 *   <li><b>过期</b>：超过 {@code createdAt + ttl} 即失效，需重新预览（边界：恰好在 TTL 上仍有效，
 *       严格晚于才过期）。</li>
 * </ul>
 *
 * <p>线程安全（{@link ConcurrentHashMap}）。它是会被多 Update 并发的单例，
 * 不假设调用顺序。
 */
public final class PendingTradeRegistry {

    private final Duration ttl;
    private final Clock clock;
    private final Map<Long, Pending> byBuyer = new ConcurrentHashMap<>();

    /** 一条待确认登记：请求 + 登记时刻。 */
    private record Pending(TradeInitiationRequest request, Instant createdAt) {
    }

    /**
     * @param ttl   登记有效期（正数）
     * @param clock 时钟（用于过期判定）
     */
    public PendingTradeRegistry(Duration ttl, Clock clock) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new EscrowException("待确认登记：有效期必须为正，实为 " + ttl);
        }
        if (clock == null) {
            throw new EscrowException("待确认登记：时钟不可为空");
        }
        this.ttl = ttl;
        this.clock = clock;
    }

    /**
     * 登记（或覆盖）某买方最近的待确认请求。
     *
     * @param request 已通过自身校验的发起请求
     */
    public void prepare(TradeInitiationRequest request) {
        if (request == null) {
            throw new EscrowException("待确认登记：请求不可为空");
        }
        byBuyer.put(request.buyerId(), new Pending(request, clock.instant()));
    }

    /**
     * 消费登记：仅当存在「同买方、参数逐字一致、未过期」的登记时，返回该请求并移除。
     *
     * @param request confirm 侧解析出的请求
     * @return 命中的请求；否则 {@link Optional#empty()}（未预览 / 参数不符 / 已过期 / 已消费）
     */
    public Optional<TradeInitiationRequest> consume(TradeInitiationRequest request) {
        if (request == null) {
            throw new EscrowException("待确认登记：请求不可为空");
        }
        Pending pending = byBuyer.get(request.buyerId());
        if (pending == null) {
            return Optional.empty();
        }
        if (expired(pending)) {
            byBuyer.remove(request.buyerId(), pending);
            return Optional.empty();
        }
        if (!pending.request().equals(request)) {
            // 参数不符：不消费，保留原登记——用户改用正确参数仍可确认
            return Optional.empty();
        }
        byBuyer.remove(request.buyerId(), pending);
        return Optional.of(request);
    }

    /** 过期判定：严格晚于 {@code createdAt + ttl} 才算过期（恰好到点仍有效）。 */
    private boolean expired(Pending pending) {
        return clock.instant().isAfter(pending.createdAt().plus(ttl));
    }
}
