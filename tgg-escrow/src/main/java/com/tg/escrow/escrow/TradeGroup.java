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

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.Instant;

/**
 * 交易群 ↔ 交易绑定（表 {@code trade_groups}）——让 {@link TradeGroupLifecycle} 的内存状态
 * 在重启后不丢。
 *
 * <h2>为什么单独一张表，而不是给 {@code escrow_orders} 加列</h2>
 * <p>「群与交易 ID 绑定」是本项目消解业务冲突 1.2 的手段（{@code docs/requirements/SPEC.md:327}：
 * 每笔新交易新建群、不复用）。这笔绑定数据是群的生命周期账，与订单账本职责不同：群是交易的
 * <b>辅助载体</b>，它的建/退/归档甚至可能失败（降级路径），不该因此牵动订单表的结构与约束。
 * 单列一张表，群侧的写入失败与订单侧彻底解耦。
 *
 * <h2>主键即交易号</h2>
 * <p>{@code trade_id} 既是主键也是唯一键——天然保证「一交易一群、不复用」。{@code chat_id}
 * 落唯一索引（可空：建群前的中间态），为后续 {@code chatId → tradeId} 反查预留。
 *
 * <h2>表结构由 Flyway 管理</h2>
 * <p>JPA 侧 {@code ddl-auto=validate}——实体不许自行建表或改表，结构漂移在启动期即失败。
 *
 * <h2>状态机为何不落在本类</h2>
 * <p>迁移规则在 {@link TradeGroupLifecycle}（纯逻辑、可穷举测试）。本类只做<b>持久化形状</b>：
 * 存状态名与两个时刻，并提供与状态机之间的双向转换，避免把迁移规则复制到实体里（两处规则必漂移）。
 */
@Entity
@Table(name = "trade_groups")
public class TradeGroup {

    @Id
    @Column(name = "trade_id", nullable = false)
    private Long tradeId;

    /**
     * 交易群 chat ID（Telegram 超级群为负数）。
     *
     * <p>可空是为建群前的中间态留位；经 {@link TradeGroupService#onTradeCreated} 建立的行必然已绑定。
     */
    @Column(name = "chat_id")
    private Long chatId;

    /** 生命周期状态名（{@link TradeGroupLifecycle.State}）。业务判断请用 {@link #currentState()}。 */
    @Column(name = "state", nullable = false, length = 16)
    private String state;

    /** 静默期起点（{@code SILENT}/{@code ARCHIVED} 态非空，其余为 {@code null}）。 */
    @Column(name = "silence_started_at")
    private Instant silenceStartedAt;

    /** 归档时刻（{@code ARCHIVED} 态非空）。 */
    @Column(name = "archived_at")
    private Instant archivedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** JPA 要求的无参构造。业务代码请用下面的公开构造器。 */
    protected TradeGroup() {
    }

    /**
     * 新建一笔群绑定：状态恒从 {@link TradeGroupLifecycle.State#PENDING} 起步。
     *
     * @param tradeId 交易号（= 订单主键）
     * @param chatId  已建成的群 chat ID
     * @param now     新建时刻
     */
    public TradeGroup(long tradeId, long chatId, Instant now) {
        if (now == null) {
            throw new EscrowException("交易群记录：新建时刻不可为空");
        }
        this.tradeId = tradeId;
        this.chatId = chatId;
        this.state = TradeGroupLifecycle.State.PENDING.name();
        this.updatedAt = now;
    }

    public Long getTradeId() {
        return tradeId;
    }

    /** 已绑定的群 chat ID；理论上经服务建立后非空。 */
    public Long getChatId() {
        return chatId;
    }

    public Instant getSilenceStartedAt() {
        return silenceStartedAt;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** 当前生命周期状态（业务读取入口）。未知值 fail-closed。 */
    public TradeGroupLifecycle.State currentState() {
        if (this.state == null) {
            throw new EscrowException("交易群状态缺失（fail-closed）");
        }
        try {
            return TradeGroupLifecycle.State.valueOf(this.state);
        } catch (IllegalArgumentException ex) {
            // 不让 JDK 异常逃逸：上层需要能识别为"数据异常、需人工介入"
            throw new EscrowException("交易群状态非法（fail-closed）：" + this.state, ex);
        }
    }

    /** 从持久化字段重建状态机（服务推进前调用）。 */
    TradeGroupLifecycle toLifecycle(Duration silence) {
        return TradeGroupLifecycle.restore(tradeId, silence, currentState(), silenceStartedAt);
    }

    /** 用推进后的状态机回写持久化字段。 */
    void applyLifecycle(TradeGroupLifecycle lifecycle, Instant now) {
        this.state = lifecycle.currentState().name();
        this.silenceStartedAt = lifecycle.silenceStartedAt();
        if (lifecycle.currentState() == TradeGroupLifecycle.State.ARCHIVED) {
            this.archivedAt = now;
        }
        this.updatedAt = now;
    }
}
