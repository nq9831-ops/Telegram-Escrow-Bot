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
import com.tg.escrow.core.WarningPolicy.Action;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 警告处置编排（GM-03 接线：WarningPolicy → ModerationOrchestrator）的行为固定测试。
 *
 * <p>串起三个零件：累计数 → {@code WarningPolicy} 判罚 → {@code ModerationOrchestrator} 执行
 * （含其单调守卫）。判罚为 NONE 时<b>零处置调用</b>（不预罚）。
 */
class WarningOrchestratorTest {

    /** fake 端口记录调用。 */
    private static final class FakePort implements GroupAdminPort {
        final List<String> calls = new ArrayList<>();

        @Override
        public void kick(long g, long u) {
            calls.add("kick:" + u);
        }

        @Override
        public void ban(long g, long u) {
            calls.add("ban:" + u);
        }

        @Override
        public void mute(long g, long u, Duration d) {
            calls.add("mute:" + u);
        }

        @Override
        public void deleteMessage(long g, long m) {
            calls.add("del:" + m);
        }
    }

    @Test
    @DisplayName("达到禁言阈值 → 自动禁言（默认时长）")
    void muteOnThreshold() {
        FakePort port = new FakePort();
        WarningOrchestrator w = new WarningOrchestrator(new WarningPolicy(3, 5),
                new ModerationOrchestrator(port), Duration.ofMinutes(10));

        Action action = w.handle(1L, new CommandActor(10L, MemberRole.ADMIN), 20L,
                MemberRole.MEMBER, 3);

        assertThat(action).isEqualTo(Action.MUTE);
        assertThat(port.calls).containsExactly("mute:20");
    }

    @Test
    @DisplayName("达到踢出阈值 → 自动踢出")
    void kickOnThreshold() {
        FakePort port = new FakePort();
        WarningOrchestrator w = new WarningOrchestrator(new WarningPolicy(3, 5),
                new ModerationOrchestrator(port), Duration.ofMinutes(10));

        Action action = w.handle(1L, new CommandActor(10L, MemberRole.ADMIN), 20L,
                MemberRole.MEMBER, 5);

        assertThat(action).isEqualTo(Action.KICK);
        assertThat(port.calls).containsExactly("kick:20");
    }

    @Test
    @DisplayName("未达阈值 → 不动作且零处置调用（不预罚）")
    void belowThresholdNoAction() {
        FakePort port = new FakePort();
        WarningOrchestrator w = new WarningOrchestrator(new WarningPolicy(3, 5),
                new ModerationOrchestrator(port), Duration.ofMinutes(10));

        Action action = w.handle(1L, new CommandActor(10L, MemberRole.ADMIN), 20L,
                MemberRole.MEMBER, 2);

        assertThat(action).isEqualTo(Action.NONE);
        assertThat(port.calls).isEmpty();
    }

    @Test
    @DisplayName("处置守卫仍生效：管理员不得处置管理员（编排链不绕过单调守卫）")
    void guardStillApplies() {
        FakePort port = new FakePort();
        WarningOrchestrator w = new WarningOrchestrator(new WarningPolicy(3, 5),
                new ModerationOrchestrator(port), Duration.ofMinutes(10));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> w.handle(1L,
                new CommandActor(10L, MemberRole.ADMIN), 21L, MemberRole.ADMIN, 5))
                .isInstanceOf(TggException.class);
        assertThat(port.calls).isEmpty();
    }

    @Test
    @DisplayName("【缺陷复现】普通成员即便未达阈值也不得记警告（现状会静默放过）")
    void memberCannotWarnBelowThreshold() {
        FakePort port = new FakePort();
        WarningOrchestrator w = new WarningOrchestrator(new WarningPolicy(3, 5),
                new ModerationOrchestrator(port), Duration.ofMinutes(10));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> w.handle(1L,
                new CommandActor(10L, MemberRole.MEMBER), 21L, MemberRole.MEMBER, 0))
                .as("未达阈值时判罚为 NONE，但「记警告」本身仍是处置行为——"
                        + "守卫若只存在于 mute/kick 内部，普通成员就能把别人的警告数刷到判罚线")
                .isInstanceOf(TggException.class);
        assertThat(port.calls).isEmpty();
    }

    @Test
    @DisplayName("管理员未达阈值 → 正常返回 NONE 且零处置调用（修缺口不得误伤正常路径）")
    void adminBelowThresholdIsNoop() {
        FakePort port = new FakePort();
        WarningOrchestrator w = new WarningOrchestrator(new WarningPolicy(3, 5),
                new ModerationOrchestrator(port), Duration.ofMinutes(10));

        Action action = w.handle(1L, new CommandActor(10L, MemberRole.ADMIN), 21L, MemberRole.MEMBER, 0);

        assertThat(action).isEqualTo(Action.NONE);
        assertThat(port.calls).isEmpty();
    }
}
