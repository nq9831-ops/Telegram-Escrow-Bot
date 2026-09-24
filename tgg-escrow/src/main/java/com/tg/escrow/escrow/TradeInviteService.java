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

import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.function.Supplier;

/**
 * 深链邀请服务——「发起人建邀请」与「对方接单」两条路径的唯一入口。
 *
 * <h2>两步流</h2>
 * <pre>
 * create(buyer, amount, currency)
 *   ├─ 查历史 → 过准入门禁（T23/T24/T25）
 *   ├─ 被拒 → REJECTED（绝不建邀请）
 *   └─ 放行 → 生成一次性令牌 → 存 TradeInvite(有效期 now+ttl) → CREATED
 *
 * accept(token, acceptor)
 *   ├─ 按令牌查邀请（查不到 → NOT_FOUND）
 *   ├─ 聚合守卫（已消费/过期/自邀自接 → 各自 Reason）
 *   ├─ 就当前时刻<b>重建</b>发起方门禁（发起方可能已不满足——否则会绕过 T23）
 *   ├─ 物化 EscrowOrder(buyer, acceptor) → OPEN → markConfirmed → CONFIRMED
 *   ├─ 标记邀请已消费（写回 acceptedOrderId）
 *   └─ [同一事务] 冲突回滚 → 订单不残留
 * </pre>
 *
 * <h2>为什么接单要重建门禁</h2>
 * <p>建邀请时发起方可能是干净的，但接单发生在之后（最长 ttl）。若不在接单时重判，
 * 一个已被争议冻结或已满并发的发起方，仍可通过「先建邀请、后接单」拿到新订单——
 * T23/T25 形同虚设。
 *
 * <h2>原子性</h2>
 * <p>{@code accept} 用 {@link Transactional} 把「落单 + 消费邀请」绑成一笔事务：
 * 并发抢先时后到者因邀请版本冲突而整体回滚，订单不会残留；两笔订单/两个接受者都不可能同时成立。
 *
 * <p>令牌由注入的 {@link Supplier} 生成，生产用 {@link #secureRandomTokenSupplier()}；
 * 时钟由构造注入使有效期判定可测、且同一路径内时刻一致。
 */
public final class TradeInviteService {

    private final TradeAdmissionGate gate;
    private final TradeHistoryPort historyPort;
    private final TradeInviteStore inviteStore;
    private final EscrowOrderStore orderStore;
    private final Duration ttl;
    private final Supplier<String> tokenSupplier;
    private final Clock clock;

    /**
     * @param gate          准入门禁（建邀请与接单两处复用）
     * @param historyPort   历史查询端口（重建发起方现状）
     * @param inviteStore   邀请存储
     * @param orderStore    订单存储（接单时物化订单）
     * @param ttl           邀请有效期（正数）
     * @param tokenSupplier 一次性令牌生成器
     * @param clock         时钟
     */
    public TradeInviteService(TradeAdmissionGate gate, TradeHistoryPort historyPort,
                              TradeInviteStore inviteStore, EscrowOrderStore orderStore,
                              Duration ttl, Supplier<String> tokenSupplier, Clock clock) {
        if (gate == null) {
            throw new EscrowException("邀请服务：未提供准入门禁");
        }
        if (historyPort == null) {
            throw new EscrowException("邀请服务：未提供历史查询端口");
        }
        if (inviteStore == null) {
            throw new EscrowException("邀请服务：未提供邀请存储");
        }
        if (orderStore == null) {
            throw new EscrowException("邀请服务：未提供订单存储");
        }
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new EscrowException("邀请服务：有效期必须为正，实为 " + ttl);
        }
        if (tokenSupplier == null) {
            throw new EscrowException("邀请服务：未提供令牌生成器");
        }
        if (clock == null) {
            throw new EscrowException("邀请服务：未提供时钟");
        }
        this.gate = gate;
        this.historyPort = historyPort;
        this.inviteStore = inviteStore;
        this.orderStore = orderStore;
        this.ttl = ttl;
        this.tokenSupplier = tokenSupplier;
        this.clock = clock;
    }

    /**
     * 以指定发起人建一条待接受邀请。
     *
     * @param buyerId  发起人（买方）ID
     * @param amount   金额（必须为正）
     * @param currency 币种（须在 TON / USDT(TON 链) 白名单内）
     * @return 创建成功（含邀请与令牌）或被拒（含原因与可重试时刻）
     * @throws EscrowException 请求不合法（金额/币种）、或历史端口故障——一律<b>不建邀请</b>
     */
    public TradeInviteCreationResult create(long buyerId, BigDecimal amount, String currency) {
        Instant now = clock.instant();

        TradeAdmissionContext context = historyPort.snapshotOf(buyerId);
        if (context.subjectId() != buyerId) {
            throw new EscrowException("邀请创建：历史归属错位——查的是 "
                    + buyerId + "，返回的是 " + context.subjectId());
        }
        TradeAdmissionDecision decision = gate.check(context, now);
        if (!decision.allowed()) {
            return TradeInviteCreationResult.rejected(decision);
        }

        // 金额/币种的合法性在聚合构造期把关（失败即抛，绝不落库）
        TradeInvite invite = new TradeInvite(buyerId, amount, currency,
                tokenSupplier.get(), now, now.plus(ttl));
        return TradeInviteCreationResult.created(inviteStore.save(invite));
    }

    /**
     * 接受一条邀请：物化订单并消费邀请。
     *
     * @param token      一次性邀请令牌（来自已验签的 {@code start_param}）
     * @param acceptorId 接受者（卖方）ID（来自已验签的身份）
     * @return 物化出的订单（状态 {@code CONFIRMED}）
     * @throws TradeInviteException 令牌无效/已接受/已过期/自邀自接/发起方不可承接
     * @throws ConcurrentOrderUpdateException 并发抢接：邀请已被他人接受
     */
    @Transactional
    public EscrowOrder accept(String token, long acceptorId) {
        if (token == null || token.isBlank()) {
            throw new TradeInviteException(TradeInviteException.Reason.NOT_FOUND, "邀请令牌未提供");
        }
        TradeInvite invite = inviteStore.byToken(token)
                .orElseThrow(() -> new TradeInviteException(TradeInviteException.Reason.NOT_FOUND,
                        "邀请不存在或链接无效"));

        Instant now = clock.instant();
        invite.requireAcceptable(acceptorId, now);

        long buyerId = invite.getBuyerUserId();
        TradeAdmissionContext context = historyPort.snapshotOf(buyerId);
        if (context.subjectId() != buyerId) {
            throw new EscrowException("邀请接受：历史归属错位——查的是 "
                    + buyerId + "，返回的是 " + context.subjectId());
        }
        TradeAdmissionDecision decision = gate.check(context, now);
        if (!decision.allowed()) {
            throw new TradeInviteException(TradeInviteException.Reason.NOT_ADMITTED,
                    "发起方当前无法承接新交易：" + reasonText(decision.reason()));
        }

        // 卖方已通过「接受」表达了确认，故订单直接进入 CONFIRMED（复用既有迁移，零新状态）
        EscrowOrder order = new EscrowOrder(buyerId, acceptorId,
                invite.getAmount(), invite.getCurrency(), now);
        order.markConfirmed(now);
        EscrowOrder saved = orderStore.save(order);

        invite.markAccepted(saved.getId());
        inviteStore.save(invite);
        return saved;
    }

    /** 生产用令牌生成器：16 字节 {@link SecureRandom} → 32 位十六进制（URL 安全、不可枚举）。 */
    public static Supplier<String> secureRandomTokenSupplier() {
        SecureRandom random = new SecureRandom();
        return () -> {
            byte[] bytes = new byte[16];
            random.nextBytes(bytes);
            return HexFormat.of().formatHex(bytes);
        };
    }

    private static String reasonText(TradeAdmissionDecision.Reason reason) {
        return switch (reason) {
            case CONCURRENT_LIMIT -> "已有进行中的交易";
            case COOLDOWN -> "冷却期未满";
            case DISPUTE_HOLD -> "存在未决争议";
            case ALLOWED -> "放行";
        };
    }
}
