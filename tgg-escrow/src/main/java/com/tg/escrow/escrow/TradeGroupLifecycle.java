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

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;

/**
 * 交易群生命周期（ET-39/40）：待建 → 已关联 → 留痕中 → 静默期 → 已归档。
 *
 * <h2>群与交易 ID 绑定（业务冲突 1.2 的解法）</h2>
 * <p>一个生命周期实例<b>在构造时即绑定唯一交易 ID</b>——每笔新交易新建群、不复用旧群。
 * 这消除"归档时无法判断群属于哪笔交易"的歧义。
 *
 * <h2>争议终止静默期</h2>
 * <p>静默期内争议发起 → 恢复 {@code RECORDING}（留痕），而不是让证据在静默中沉默消失。
 * 归档为终态：归档后不得再争议（群已退出，留痕由服务端保留——见核心规则"群聊归档"）。
 *
 * <p>归档/静默判定为<b>闭区间</b>（{@code now >= 进入时刻 + 7 天}），与项目内其它时限语义一致。
 * 本类有状态、非线程安全：单个交易群由单一处理线程推进。
 */
public final class TradeGroupLifecycle {

    /** 交易群状态。 */
    public enum State {
        /** 待建（交易已创建，群尚未拉起）。 */
        PENDING,
        /** 已关联（群已建并与交易绑定）。 */
        LINKED,
        /** 留痕中（机器人记录群内消息）。 */
        RECORDING,
        /** 静默期（完成后 7 天静默）。 */
        SILENT,
        /** 已归档（终态：机器人退出，服务端保留记录）。 */
        ARCHIVED
    }

    private final long tradeId;
    private final Duration silence;
    private State state = State.PENDING;
    private Instant silenceStartedAt;

    /**
     * @param tradeId 交易 ID（构造即绑定，一交易一群）
     * @param silence 静默期时长（正数，默认 7 天由调用方给定）
     */
    public TradeGroupLifecycle(long tradeId, Duration silence) {
        if (silence == null || silence.isZero() || silence.isNegative()) {
            throw new EscrowException("交易群：静默期时长必须为正，实为 " + silence);
        }
        this.tradeId = tradeId;
        this.silence = silence;
    }

    /** 绑定的交易 ID。 */
    public long tradeId() {
        return tradeId;
    }

    /** 当前状态。 */
    public State currentState() {
        return state;
    }

    /** 群已拉起并与交易关联：{@code PENDING} → {@code LINKED}。 */
    public void link(Instant now) {
        require(State.PENDING, "关联交易群");
        state = State.LINKED;
    }

    /** 开始留痕：{@code LINKED} → {@code RECORDING}。 */
    public void startRecording(Instant now) {
        require(State.LINKED, "开始留痕");
        state = State.RECORDING;
    }

    /** 交易完成进入静默期：{@code RECORDING} → {@code SILENT}。 */
    public void enterSilence(Instant now) {
        require(State.RECORDING, "进入静默期");
        silenceStartedAt = now;
        state = State.SILENT;
    }

    /**
     * 争议发起终止静默期：{@code SILENT} → {@code RECORDING}（恢复留痕）。
     *
     * <p>只允许从 {@code SILENT} 发起：留痕中无需"终止静默"，归档后群已退出更不得再争议。
     */
    public void disputeOpened(Instant now) {
        require(State.SILENT, "争议终止静默期");
        state = State.RECORDING;
    }

    /** 归档：{@code SILENT} → {@code ARCHIVED}（终态）。要求静默期已满（闭区间）。 */
    public void archive(Instant now) {
        require(State.SILENT, "归档");
        if (!isArchiveDue(now)) {
            throw new EscrowException("交易群：静默期未满，不得归档（截止 "
                    + silenceStartedAt.plus(silence) + "）");
        }
        state = State.ARCHIVED;
    }

    /** 归档是否到期（{@code now >= 静默开始 + 静默期}，闭区间）。仅 {@code SILENT} 状态有意义。 */
    public boolean isArchiveDue(Instant now) {
        if (now == null) {
            throw new EscrowException("交易群：当前时刻不可为空");
        }
        return silenceStartedAt != null && !now.isBefore(silenceStartedAt.plus(silence));
    }

    private void require(State expected, String action) {
        if (state != expected) {
            throw new EscrowException("交易群：" + action + " 非法——当前 " + state
                    + "，要求 " + expected + Arrays.toString(State.values()));
        }
    }
}
