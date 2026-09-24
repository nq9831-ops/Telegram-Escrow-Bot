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
import com.tg.escrow.core.WarningOrchestrator;
import com.tg.escrow.core.WarningPolicy;
import com.tg.escrow.moderation.WarningPort;
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

    /** 记录型 fake 警告端口：把「警告到底记没记上」变成可断言的物理事实。 */
    static final class FakeWarningPort implements WarningPort {
        private final java.util.Map<Long, Integer> counts = new java.util.HashMap<>();
        final List<String> cleared = new ArrayList<>();

        @Override
        public int warn(long guildId, long userId) {
            int next = counts.getOrDefault(userId, 0) + 1;
            counts.put(userId, next);
            return next;
        }

        @Override
        public int countOf(long guildId, long userId) {
            return counts.getOrDefault(userId, 0);
        }

        @Override
        public void clear(long guildId, long userId) {
            counts.remove(userId);
            cleared.add("clear:" + userId);
        }
    }

    /** 目标一律是普通成员（除专门用例）。警告策略：满 1 次即禁言。 */
    private static ModerationCommandHandler handler(RecordingPort port) {
        return handler(port, new FakeWarningPort());
    }

    private static ModerationCommandHandler handler(RecordingPort port, FakeWarningPort warnings) {
        MemberRolePort roles = (chatId, userId) -> MemberRole.MEMBER;
        return new ModerationCommandHandler(new ModerationOrchestrator(port), roles, warnings,
                new WarningOrchestrator(new WarningPolicy(1, 5),
                        new ModerationOrchestrator(port), Duration.ofMinutes(10)));
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
        ModerationCommandHandler h = new ModerationCommandHandler(new ModerationOrchestrator(port), roles,
                new FakeWarningPort(),
                new WarningOrchestrator(new WarningPolicy(3, 5), new ModerationOrchestrator(port),
                        Duration.ofMinutes(10)));

        String out = h.handle(cmd("kick", String.valueOf(TARGET)), ADMIN_ACTOR, CHAT);

        assertThat(out).isNotEmpty();
        assertThat(port.calls).isEmpty();
    }

    // ── 警告累计（Wave 2）──────────────────────────────────────────────────

    @Test
    @DisplayName("canHandle 认 warn / unwarn")
    void canHandleWarnCommands() {
        ModerationCommandHandler h = handler(new RecordingPort());

        assertThat(h.canHandle(cmd("warn", String.valueOf(TARGET)))).isTrue();
        assertThat(h.canHandle(cmd("unwarn", String.valueOf(TARGET)))).isTrue();
    }

    @Test
    @DisplayName("【反证】普通成员 /warn → 拒，且【警告端口零记录】（拒绝必须发生在记录之前）")
    void memberCannotWarnAndNothingRecorded() {
        RecordingPort port = new RecordingPort();
        FakeWarningPort warnings = new FakeWarningPort();

        String out = handler(port, warnings).handle(cmd("warn", String.valueOf(TARGET)), MEMBER_ACTOR, CHAT);

        assertThat(out).contains("无权");
        assertThat(warnings.countOf(CHAT, TARGET))
                .as("若先记录再鉴权，普通成员就能把别人的警告数刷到判罚线——所以断言零记录")
                .isZero();
    }

    @Test
    @DisplayName("管理员 /warn → 累计 +1 并回报次数")
    void adminWarnIncrements() {
        FakeWarningPort warnings = new FakeWarningPort();
        ModerationCommandHandler h = handler(new RecordingPort(), warnings);

        String out = h.handle(cmd("warn", String.valueOf(TARGET)), ADMIN_ACTOR, CHAT);

        assertThat(out).contains("累计 1 次");
        assertThat(warnings.countOf(CHAT, TARGET)).isEqualTo(1);
    }

    @Test
    @DisplayName("达阈值 → 真的执行判罚（禁言端口被调用）")
    void warnTriggersPenalty() {
        RecordingPort port = new RecordingPort();

        String out = handler(port).handle(cmd("warn", String.valueOf(TARGET)), ADMIN_ACTOR, CHAT);

        assertThat(port.calls).containsExactly("mute:" + CHAT + ":" + TARGET + ":PT10M");
        assertThat(out).contains("累计 1 次");
    }

    @Test
    @DisplayName("判罚失败（目标同级）→ 回执【两件都说】：警告已记、处罚未执行")
    void warnReportsBothWhenPenaltyRejected() {
        RecordingPort port = new RecordingPort();
        FakeWarningPort warnings = new FakeWarningPort();
        MemberRolePort roles = (chatId, userId) -> MemberRole.ADMIN;   // 目标与执行者同级
        ModerationCommandHandler h = new ModerationCommandHandler(
                new ModerationOrchestrator(port), roles, warnings,
                new WarningOrchestrator(new WarningPolicy(1, 5),
                        new ModerationOrchestrator(port), Duration.ofMinutes(10)));

        String out = h.handle(cmd("warn", String.valueOf(TARGET)), ADMIN_ACTOR, CHAT);

        assertThat(out).contains("累计 1 次");
        assertThat(out)
                .as("不能只报成功——管理员会以为人已被禁言")
                .contains("未执行");
        assertThat(port.calls).isEmpty();
    }

    @Test
    @DisplayName("/unwarn：管理员可清除；普通成员被拒且零调用")
    void unwarnGuards() {
        FakeWarningPort warnings = new FakeWarningPort();
        warnings.warn(CHAT, TARGET);
        ModerationCommandHandler h = handler(new RecordingPort(), warnings);

        assertThat(h.handle(cmd("unwarn", String.valueOf(TARGET)), MEMBER_ACTOR, CHAT)).contains("无权");
        assertThat(warnings.cleared).isEmpty();

        assertThat(h.handle(cmd("unwarn", String.valueOf(TARGET)), ADMIN_ACTOR, CHAT)).contains("清除");
        assertThat(warnings.countOf(CHAT, TARGET)).isZero();
    }

    @Test
    @DisplayName("/warn 参数缺失或非法 → 用法说明，且零记录")
    void warnBadArgs() {
        FakeWarningPort warnings = new FakeWarningPort();
        ModerationCommandHandler h = handler(new RecordingPort(), warnings);

        assertThat(h.handle(cmd("warn"), ADMIN_ACTOR, CHAT)).contains("用法");
        assertThat(h.handle(cmd("warn", "abc"), ADMIN_ACTOR, CHAT)).contains("用法");
        assertThat(h.handle(cmd("unwarn"), ADMIN_ACTOR, CHAT)).contains("用法");

        assertThat(warnings.countOf(CHAT, TARGET)).isZero();
    }

    @Test
    @DisplayName("构造：依赖缺失 → 抛")
    void missingDepsFailFast() {
        WarningOrchestrator warn = new WarningOrchestrator(new WarningPolicy(3, 5),
                new ModerationOrchestrator(new RecordingPort()), Duration.ofMinutes(10));
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> new ModerationCommandHandler(null, (c, u) -> MemberRole.MEMBER,
                        new FakeWarningPort(), warn)))
                .isInstanceOf(com.tg.escrow.common.TggException.class);
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> new ModerationCommandHandler(new ModerationOrchestrator(new RecordingPort()), null,
                        new FakeWarningPort(), warn)))
                .isInstanceOf(com.tg.escrow.common.TggException.class);
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> new ModerationCommandHandler(new ModerationOrchestrator(new RecordingPort()),
                        (c, u) -> MemberRole.MEMBER, null, warn)))
                .isInstanceOf(com.tg.escrow.common.TggException.class);
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> new ModerationCommandHandler(new ModerationOrchestrator(new RecordingPort()),
                        (c, u) -> MemberRole.MEMBER, new FakeWarningPort(), null)))
                .isInstanceOf(com.tg.escrow.common.TggException.class);
    }
}
