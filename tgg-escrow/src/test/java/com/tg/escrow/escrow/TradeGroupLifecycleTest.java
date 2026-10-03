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

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 交易群生命周期（ET-39/40）的行为固定测试。
 *
 * <p>两条硬约束：①<b>群与交易 ID 绑定</b>（每笔新交易新建群、不复用——业务冲突 1.2 的解法）；
 * ②<b>争议终止静默期</b>（静默期需要留痕时恢复留痕，而不是让证据沉默消失）。
 */
class TradeGroupLifecycleTest {

    private static final Instant T0 = Instant.parse("2026-09-23T00:00:00Z");
    private static final Duration SILENCE = Duration.ofDays(7);

    private static TradeGroupLifecycle fresh() {
        return new TradeGroupLifecycle(4242L, SILENCE);
    }

    @Test
    @DisplayName("构造即绑定交易 ID（一交易一群，不复用）")
    void bindsTradeIdAtConstruction() {
        TradeGroupLifecycle g = fresh();

        assertThat(g.tradeId()).isEqualTo(4242L);
        assertThat(g.currentState()).isEqualTo(State.PENDING);
    }

    @Test
    @DisplayName("五态正向迁移：待建→已关联→留痕中→静默期→已归档")
    void forwardTransitions() {
        TradeGroupLifecycle g = fresh();

        g.link(T0);
        assertThat(g.currentState()).isEqualTo(State.LINKED);

        g.startRecording(T0);
        assertThat(g.currentState()).isEqualTo(State.RECORDING);

        g.enterSilence(T0);
        assertThat(g.currentState()).isEqualTo(State.SILENT);

        g.archive(T0.plus(SILENCE));
        assertThat(g.currentState()).isEqualTo(State.ARCHIVED);
    }

    @Test
    @DisplayName("静默期未满不得归档——归档判定为闭区间（now >= silenceStart+7 天）")
    void cannotArchiveBeforeSilenceExpires() {
        TradeGroupLifecycle g = fresh();
        g.link(T0);
        g.startRecording(T0);
        g.enterSilence(T0);

        assertThat(g.isArchiveDue(T0.plus(SILENCE).minusSeconds(1))).isFalse();
        assertThat(g.isArchiveDue(T0.plus(SILENCE))).isTrue();
    }

    @Test
    @DisplayName("争议终止静默期：SILENT → RECORDING（恢复留痕）")
    void disputeTerminatesSilence() {
        TradeGroupLifecycle g = fresh();
        g.link(T0);
        g.startRecording(T0);
        g.enterSilence(T0);

        g.disputeOpened(T0.plusSeconds(3600));

        assertThat(g.currentState()).isEqualTo(State.RECORDING);
    }

    @Test
    @DisplayName("非法迁移 fail-closed：PENDING 直接 enterSilence → 拒绝")
    void illegalTransitionFailsClosed() {
        TradeGroupLifecycle g = fresh();

        assertThatThrownBy(() -> g.enterSilence(T0)).isInstanceOf(EscrowException.class);
    }

    @Test
    @DisplayName("进入静默期时时刻为空 → 当场拒绝（不把错误推迟成 archive 里的 NPE）")
    void enterSilenceRejectsNullInstant() {
        TradeGroupLifecycle g = fresh();
        g.link(T0);
        g.startRecording(T0);

        assertThatThrownBy(() -> g.enterSilence(null)).isInstanceOf(EscrowException.class);
        assertThat(g.currentState()).isEqualTo(State.RECORDING);   // 状态未被污染
    }

    @Test
    @DisplayName("终态 ARCHIVED 不可再迁移（含再次争议）")
    void archivedIsTerminal() {
        TradeGroupLifecycle g = fresh();
        g.link(T0);
        g.startRecording(T0);
        g.enterSilence(T0);
        g.archive(T0.plus(SILENCE));

        assertThatThrownBy(() -> g.disputeOpened(T0.plusSeconds(1)))
                .isInstanceOf(EscrowException.class);
    }

    @Test
    @DisplayName("非正静默期时长 → 构造期拒绝")
    void invalidSilenceRejected() {
        assertThatThrownBy(() -> new TradeGroupLifecycle(1L, Duration.ZERO))
                .isInstanceOf(EscrowException.class);
    }

    @Test
    @DisplayName("争议终止静默期后清空静默起点——非静默态不残留旧起点（否则归档判定会误判到期）")
    void disputeClearsSilenceStart() {
        TradeGroupLifecycle g = fresh();
        g.link(T0);
        g.startRecording(T0);
        g.enterSilence(T0);

        g.disputeOpened(T0.plusSeconds(1));

        assertThat(g.silenceStartedAt()).isNull();
    }

    @Test
    @DisplayName("恢复：静默态必须带静默起点，否则拒绝（否则 archive 拼错误信息时 NPE）")
    void restoreRequiresSilenceStartForSilent() {
        assertThatThrownBy(() -> TradeGroupLifecycle.restore(4242L, SILENCE, State.SILENT, null))
                .isInstanceOf(EscrowException.class);
        assertThatThrownBy(() -> TradeGroupLifecycle.restore(4242L, SILENCE, State.ARCHIVED, null))
                .isInstanceOf(EscrowException.class);
    }

    @Test
    @DisplayName("恢复：非静默态不得带残留静默起点（保持「起点 ⇔ 静默/归档」不变量）")
    void restoreRejectsStaleSilenceStart() {
        assertThatThrownBy(() -> TradeGroupLifecycle.restore(4242L, SILENCE, State.RECORDING, T0))
                .isInstanceOf(EscrowException.class);
    }

    @Test
    @DisplayName("恢复：SILENT 态带起点可继续推进归档（重启后不丢状态）")
    void restoreResumesArchiveJudgement() {
        TradeGroupLifecycle g = TradeGroupLifecycle.restore(4242L, SILENCE, State.SILENT, T0);

        assertThat(g.currentState()).isEqualTo(State.SILENT);
        assertThat(g.silenceStartedAt()).isEqualTo(T0);
        assertThat(g.isArchiveDue(T0.plus(SILENCE).minusSeconds(1))).isFalse();
        assertThat(g.isArchiveDue(T0.plus(SILENCE))).isTrue();

        g.archive(T0.plus(SILENCE));
        assertThat(g.currentState()).isEqualTo(State.ARCHIVED);
    }
}
