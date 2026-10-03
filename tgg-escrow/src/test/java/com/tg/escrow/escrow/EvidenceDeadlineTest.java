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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 证据提交窗口（ET-45）的行为固定测试。
 *
 * <p>两条硬规则：①<b>固定 24 小时窗口、不随维护期暂停</b>（业务冲突 2.3 的解法——
 * 窗口只认争议发起时刻，本类<b>刻意不提供任何暂停入口</b>）；②超时视为放弃（闭区间边界）。
 */
class EvidenceDeadlineTest {

    private static final Instant T0 = Instant.parse("2026-09-23T00:00:00Z");

    private static EvidenceDeadline deadline() {
        return new EvidenceDeadline(T0, Duration.ofHours(24));
    }

    @Test
    @DisplayName("窗口内可提交")
    void canSubmitWithinWindow() {
        EvidenceDeadline d = deadline();

        assertThat(d.canSubmit(T0)).isTrue();
        assertThat(d.canSubmit(T0.plus(Duration.ofHours(23)))).isTrue();
    }

    @Test
    @DisplayName("恰好 24 小时即到期（闭区间：now >= openedAt+24h 视为放弃）")
    void expiresAtExactly24h() {
        EvidenceDeadline d = deadline();

        assertThat(d.isExpired(T0.plus(Duration.ofHours(24)))).isTrue();
        assertThat(d.isExpired(T0.plus(Duration.ofHours(24)).minusNanos(1))).isFalse();
    }

    @Test
    @DisplayName("超时后 canSubmit 为 false（视为放弃）")
    void cannotSubmitAfterExpiry() {
        EvidenceDeadline d = deadline();

        assertThat(d.canSubmit(T0.plus(Duration.ofHours(25)))).isFalse();
    }

    @Test
    @DisplayName("窗口只从争议发起时刻算——本类无暂停入口（不随维护期暂停，业务冲突 2.3）")
    void windowIsNotPausable() {
        EvidenceDeadline d = deadline();

        // 若有人加了 pause/resume，这条测试的语义前提即被破坏：
        // deadlineAt() 必须恒等于 openedAt+window，不受任何"暂停时长"影响。
        assertThat(d.deadlineAt()).isEqualTo(T0.plus(Duration.ofHours(24)));
    }

    @Test
    @DisplayName("非正窗口 / 空发起时刻 → 构造期拒绝")
    void invalidConfigRejected() {
        assertThatThrownBy(() -> new EvidenceDeadline(T0, Duration.ZERO))
                .isInstanceOf(EscrowException.class);
        assertThatThrownBy(() -> new EvidenceDeadline(null, Duration.ofHours(24)))
                .isInstanceOf(EscrowException.class);
    }
}
