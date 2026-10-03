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
import com.tg.escrow.escrow.EvidenceChannel.Report;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 证据双通道（ET-43）的行为固定测试。
 *
 * <p>核心规则「证据双通道：机器人实时记录 + 关键节点上链存证」的硬要求是
 * <b>两条通道各自成功/失败分别上报</b>——一条失败不得静默吞掉另一条的结果。
 * 测试覆盖：全成功、单侧失败、双失败，断言两个状态总是成对出现。
 */
class EvidenceChannelTest {

    private static final String EVIDENCE = "争议证据 #1：物流单号照片哈希";

    private static EvidenceChannel ok() {
        return new EvidenceChannel(
                e -> { /* 群内留痕成功 */ },
                e -> { /* 上链存证成功 */ });
    }

    @Test
    @DisplayName("双通道成功 → 两个状态都为 true")
    void bothSucceed() {
        Report r = ok().record(EVIDENCE);

        assertThat(r.groupLogSaved()).isTrue();
        assertThat(r.chainSaved()).isTrue();
    }

    @Test
    @DisplayName("上链失败不影响群内留痕——两条各自上报（不静默丢一条）")
    void chainFailureDoesNotSwallowGroupLog() {
        EvidenceChannel c = new EvidenceChannel(
                e -> { },
                e -> { throw new EscrowException("链上存证失败（模拟）"); });

        Report r = c.record(EVIDENCE);

        assertThat(r.groupLogSaved()).isTrue();
        assertThat(r.chainSaved()).isFalse();
    }

    @Test
    @DisplayName("群内留痕失败不影响上链——两条各自上报")
    void groupLogFailureDoesNotSwallowChain() {
        EvidenceChannel c = new EvidenceChannel(
                e -> { throw new EscrowException("群内记录失败（模拟）"); },
                e -> { });

        Report r = c.record(EVIDENCE);

        assertThat(r.groupLogSaved()).isFalse();
        assertThat(r.chainSaved()).isTrue();
    }

    @Test
    @DisplayName("双失败 → 两个状态都为 false（而非抛异常吞掉整条记录请求）")
    void bothFailReportedSeparately() {
        EvidenceChannel c = new EvidenceChannel(
                e -> { throw new EscrowException("群内记录失败（模拟）"); },
                e -> { throw new EscrowException("链上存证失败（模拟）"); });

        Report r = c.record(EVIDENCE);

        assertThat(r.groupLogSaved()).isFalse();
        assertThat(r.chainSaved()).isFalse();
    }

    @Test
    @DisplayName("双通道全成才算 complete；任一失败即 partial")
    void completeRequiresBoth() {
        assertThat(ok().record(EVIDENCE).complete()).isTrue();

        EvidenceChannel half = new EvidenceChannel(
                e -> { }, e -> { throw new EscrowException("模拟"); });
        assertThat(half.record(EVIDENCE).complete()).isFalse();
    }

    @Test
    @DisplayName("通道未配置（null）→ 构造期 fail-closed（上链可选须显式声明空实现）")
    void nullPortRejected() {
        assertThatThrownBy(() -> new EvidenceChannel(null, e -> { }))
                .isInstanceOf(EscrowException.class);
        assertThatThrownBy(() -> new EvidenceChannel(e -> { }, null))
                .isInstanceOf(EscrowException.class);
    }
}
