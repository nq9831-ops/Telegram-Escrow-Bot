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
package com.tg.escrow.core;

import com.tg.escrow.common.TggException;
import com.tg.escrow.core.NoticePolicy.Ephemerality;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 通知三维度策略（GM-36，SPEC 6.6）的行为固定测试。
 *
 * <p>三维度互相独立且<b>不得混淆</b>：可见范围（永久/临时）、是否推送（响铃/静默）、是否留存。
 * 两条不变量：①<b>临时消息不可留存</b>（仅单人可见，留存语义自相矛盾）；
 * ②<b>关键节点恒为永久 + 留存</b>（证据保全，来自 08 的 2.4）。
 */
class NoticePolicyTest {

    @Test
    @DisplayName("关键节点：永久 + 推送 + 留存（证据保全不变量）")
    void criticalIsPermanentPushedRetained() {
        NoticePolicy n = NoticePolicy.critical();

        assertThat(n.ephemerality()).isEqualTo(Ephemerality.PERMANENT);
        assertThat(n.silent()).isFalse();
        assertThat(n.retained()).isTrue();
    }

    @Test
    @DisplayName("中间进度：永久但静默——静默 ≠ 临时（消息可见、可留存）")
    void progressIsSilentButPermanent() {
        NoticePolicy n = NoticePolicy.progress();

        assertThat(n.ephemerality()).isEqualTo(Ephemerality.PERMANENT);
        assertThat(n.silent()).isTrue();
        assertThat(n.retained()).isTrue();
    }

    @Test
    @DisplayName("隐私提示：临时（仅单人可见）且不留存")
    void privacyHintIsEphemeralNotRetained() {
        NoticePolicy n = NoticePolicy.privacyHint();

        assertThat(n.ephemerality()).isEqualTo(Ephemerality.EPHEMERAL);
        assertThat(n.retained()).isFalse();
    }

    @Test
    @DisplayName("临时消息 × 留存 → 构造期拒绝（语义矛盾 fail-closed）")
    void ephemeralCannotBeRetained() {
        assertThatThrownBy(() -> new NoticePolicy(Ephemerality.EPHEMERAL, false, true))
                .isInstanceOf(TggException.class);
    }

    @Test
    @DisplayName("null 维度 → 构造期拒绝")
    void nullEphemeralityRejected() {
        assertThatThrownBy(() -> new NoticePolicy(null, true, true))
                .isInstanceOf(TggException.class);
    }
}
