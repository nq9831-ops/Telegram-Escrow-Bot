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
package com.tg.escrow;

import com.tg.escrow.core.BotCommand;
import com.tg.escrow.core.CommandActor;
import com.tg.escrow.core.GroupAdminPort;
import com.tg.escrow.core.MemberRole;
import com.tg.escrow.core.MemberRolePort;
import com.tg.escrow.core.ModerationOrchestrator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 群管理命令处理器的行为固定测试（Wave 3 接线）。
 *
 * <h2>它守的是什么</h2>
 * <p>用<b>真实</b> {@link ModerationOrchestrator} + 真实角色守卫 + 记录型 fake
 * {@link GroupAdminPort}——不 mock 中间层。最重要的断言不是文案，而是
 * <b>「被拒时端口零调用」</b>：只断言回执文案会漏掉"文案说拒绝了、动作却真的执行了"这种
 * 最危险的形态（那是越权，不只是提示错误）。
 */
class ModerationCommandHandlerTest {

    private static final long CHAT = -1001234567890L;
    private static final long ADMIN = 1001L;
    private static final long MEMBER = 2002L;
    private static final long TARGET = 3003L;

    private static final CommandActor ADMIN_ACTOR = new CommandActor(ADMIN, MemberRole.ADMIN);
    private static final CommandActor MEMBER_ACTOR = new CommandActor(MEMBER, MemberRole.MEMBER);

    /** 记录型 fake 端口：把「有没有真的动手」变成可断言的物理事实。 */
    static final class RecordingPort implements GroupAdminPort {
        final List<String> calls = new ArrayList<>();

        @Override
        public void kick(long guildId, long userId) {
            calls.add("kick:" + guildId + ":" + userId);
        }

        @Override
        public void ban(long guildId, long userId) {
            calls.add("ban:" + guildId + ":" + userId);
        }

        @Override
        public void mute(long guildId, long userId, Duration duration) {
            calls.add("mute:" + guildId + ":" + userId + ":" + duration);
        }

        @Override
        public void deleteMessage(long guildId, long messageId) {
            calls.add("del:" + guildId + ":" + messageId);
        }
    }

    /** 目标一律是普通成员（除专门用例）。 */
    private static ModerationCommandHandler handler(RecordingPort port) {
        MemberRolePort roles = (chatId, userId) -> MemberRole.MEMBER;
        return new ModerationCommandHandler(new ModerationOrchestrator(port), roles);
    }

    private static BotCommand cmd(String name, String... args) {
        return new BotCommand(name, List.of(args));
    }

    @Test
    @DisplayName("canHandle 认 kick/ban/mute/del，不认其它")
    void canHandleOnlyModerationCommands() {
        ModerationCommandHandler h = handler(new RecordingPort());

        assertThat(h.canHandle(cmd("kick", "1"))).isTrue();
        assertThat(h.canHandle(cmd("ban", "1"))).isTrue();
        assertThat(h.canHandle(cmd("mute", "1", "30"))).isTrue();
        assertThat(h.canHandle(cmd("del", "1"))).isTrue();
        assertThat(h.canHandle(cmd("escrow", "create"))).isFalse();
        assertThat(h.canHandle(null)).isFalse();
    }

    @Test
    @DisplayName("管理员 /kick → 端口被真实调用，回执确认")
    void adminCanKick() {
        RecordingPort port = new RecordingPort();

        String out = handler(port).handle(cmd("kick", String.valueOf(TARGET)), ADMIN_ACTOR, CHAT);

        assertThat(port.calls).containsExactly("kick:" + CHAT + ":" + TARGET);
        assertThat(out).contains("踢");
    }

    @Test
    @DisplayName("管理员 /ban、/mute、/del 各自落到对应端口")
    void adminCanBanMuteDelete() {
        RecordingPort port = new RecordingPort();
        ModerationCommandHandler h = handler(port);

        h.handle(cmd("ban", String.valueOf(TARGET)), ADMIN_ACTOR, CHAT);
        h.handle(cmd("mute", String.valueOf(TARGET), "30"), ADMIN_ACTOR, CHAT);
        h.handle(cmd("del", "77"), ADMIN_ACTOR, CHAT);

        assertThat(port.calls).containsExactly(
                "ban:" + CHAT + ":" + TARGET,
                "mute:" + CHAT + ":" + TARGET + ":PT30M",
                "del:" + CHAT + ":77");
    }

    @Test
    @DisplayName("【反证】普通成员发 /kick → 拒，且【端口零调用】（文案对了、动作却执行了才是最危险的）")
    void memberCannotKickAndNothingHappens() {
        RecordingPort port = new RecordingPort();

        String out = handler(port).handle(cmd("kick", String.valueOf(TARGET)), MEMBER_ACTOR, CHAT);

        assertThat(out).contains("无法").contains("权限");
        assertThat(port.calls)
                .as("权限不足时绝不能真的动手——所以要断言端口零调用，而不只是断言文案")
                .isEmpty();
    }

    @Test
    @DisplayName("普通成员发 /ban、/mute、/del → 同样被拒且零调用")
    void memberCannotBanMuteDelete() {
        RecordingPort port = new RecordingPort();
        ModerationCommandHandler h = handler(port);

        h.handle(cmd("ban", String.valueOf(TARGET)), MEMBER_ACTOR, CHAT);
        h.handle(cmd("mute", String.valueOf(TARGET), "30"), MEMBER_ACTOR, CHAT);
        h.handle(cmd("del", "77"), MEMBER_ACTOR, CHAT);

        assertThat(port.calls).isEmpty();
    }

    @Test
    @DisplayName("参数缺失 / 非法 → 用法说明，且端口零调用")
    void badArgsReturnUsage() {
        RecordingPort port = new RecordingPort();
        ModerationCommandHandler h = handler(port);

        assertThat(h.handle(cmd("kick"), ADMIN_ACTOR, CHAT)).contains("用法");
        assertThat(h.handle(cmd("kick", "abc"), ADMIN_ACTOR, CHAT)).contains("用法");
        assertThat(h.handle(cmd("mute", String.valueOf(TARGET)), ADMIN_ACTOR, CHAT)).contains("用法");
        assertThat(h.handle(cmd("mute", String.valueOf(TARGET), "0"), ADMIN_ACTOR, CHAT)).contains("用法");
        assertThat(h.handle(cmd("mute", String.valueOf(TARGET), "-5"), ADMIN_ACTOR, CHAT)).contains("用法");
        assertThat(h.handle(cmd("del", "abc"), ADMIN_ACTOR, CHAT)).contains("用法");

        assertThat(port.calls).isEmpty();
    }

    @Test
    @DisplayName("【反证】目标角色不低于执行者 → 拒且零调用（单调守卫：管理员不能踢同级/群主）")
    void cannotActOnEqualOrHigherRole() {
        RecordingPort port = new RecordingPort();
        // 目标也是管理员（与执行者同级）
        MemberRolePort roles = (chatId, userId) -> MemberRole.ADMIN;
        ModerationCommandHandler h = new ModerationCommandHandler(new ModerationOrchestrator(port), roles);

        String out = h.handle(cmd("kick", String.valueOf(TARGET)), ADMIN_ACTOR, CHAT);

        assertThat(out).isNotEmpty();
        assertThat(port.calls).isEmpty();
    }

    @Test
    @DisplayName("构造：依赖缺失 → 抛")
    void missingDepsFailFast() {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> new ModerationCommandHandler(null, (c, u) -> MemberRole.MEMBER)))
                .isInstanceOf(com.tg.escrow.common.TggException.class);
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> new ModerationCommandHandler(new ModerationOrchestrator(new RecordingPort()), null)))
                .isInstanceOf(com.tg.escrow.common.TggException.class);
    }
}
