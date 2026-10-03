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

import org.springframework.dao.OptimisticLockingFailureException;

import java.util.Optional;

/**
 * {@link TradeInviteStore} 的 JPA 实现——落库到 {@code trade_invites} 表。
 *
 * <p>与 {@link JpaEscrowOrderStore} 同一手法：乐观锁冲突（技术异常）在适配器边界转译为
 * {@link ConcurrentOrderUpdateException}，让上层能据此回「已被他人接受（请重查）」而非
 * 「操作非法」。落单结果未知时不谎报成功。
 */
public final class JpaTradeInviteStore implements TradeInviteStore {

    private final TradeInviteRepository repository;

    public JpaTradeInviteStore(TradeInviteRepository repository) {
        if (repository == null) {
            throw new EscrowException("邀请存储：未提供仓储");
        }
        this.repository = repository;
    }

    @Override
    public TradeInvite save(TradeInvite invite) {
        if (invite == null) {
            throw new EscrowException("邀请存储：不得保存 null 邀请");
        }
        TradeInvite saved;
        try {
            saved = repository.save(invite);
        } catch (OptimisticLockingFailureException ex) {
            // 并发抢接：本条邀请已被他人接受，本次写入基于过期快照。
            throw new ConcurrentOrderUpdateException(
                    "邀请已被他人接受（并发抢接冲突），请重查", ex);
        }
        if (saved == null) {
            throw new EscrowException("邀请存储：保存返回 null，结果未知");
        }
        return saved;
    }

    @Override
    public Optional<TradeInvite> byToken(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return repository.findByToken(token);
    }
}
