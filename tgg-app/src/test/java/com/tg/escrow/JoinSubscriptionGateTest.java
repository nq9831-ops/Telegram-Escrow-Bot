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

import com.tg.escrow.core.ChannelMembershipPort;
import com.tg.escrow.core.ChannelSubscriptionCheck;
import com.tg.escrow.core.GroupAdminPort;
import com.tg.escrow.core.MemberJoined;
import com.tg.escrow.core.ProtectionMode;
import com.tg.escrow.core.WelcomeTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 入群订阅门卫（GM-16 / T60）的行为固定测试。
 *
 * <p>除了对外动作端口（{@link GroupAdminPort}，接口宽、且本类只关心 kick）用 mock，
 * 其余协作者都是真实的：真实的 {@link ChannelSubscriptionCheck} 判定、真实的
 * {@link MemberJoinHandler} 委派——mock 掉它们就测不出"查不清时不放行"这类边界。
 */
class JoinSubscriptionGateTest {

    private static final long CHAT = -100L;
    private static final long USER = 42L;
    private static final String REQUIRED = "@must_join";
    private static final MemberJoined JOINED = new MemberJoined(CHAT, "测试群", USER, "小明");

    private static MemberJoinHandler inner() {
        return new MemberJoinHandler(new ProtectionMode(), new WelcomeTemplate("欢迎 {username} 加入"));
    }

    private static JoinSubscriptionGate gate(GroupAdminPort admin,
                                             ChannelMembershipPort.Membership membership) {
        return new JoinSubscriptionGate(
                new ChannelSubscriptionCheck(Set.of(REQUIRED)),
                (channel, userId) -> membership,
                admin, inner(), Set.of(REQUIRED));
    }

    @Test
    @DisplayName("明确未订阅 → 真的踢出去 + 回执要求先订阅（门禁要执行，不能只是叨叨）")
    void notSubscribedIsKicked() {
        GroupAdminPort admin = mock(GroupAdminPort.class);

        Optional<String> out = gate(admin, ChannelMembershipPort.Membership.NOT_SUBSCRIBED)
                .onMemberJoined(JOINED);

        verify(admin).kick(CHAT, USER);
        assertThat(out).isPresent();
        assertThat(out.get()).contains(REQUIRED);
    }

    @Test
    @DisplayName("已订阅 → 不踢，并委派给入群处理器（发出欢迎语）")
    void subscribedDelegatesToWelcome() {
        GroupAdminPort admin = mock(GroupAdminPort.class);

        Optional<String> out = gate(admin, ChannelMembershipPort.Membership.SUBSCRIBED)
                .onMemberJoined(JOINED);

        verify(admin, never()).kick(anyLong(), anyLong());
        assertThat(out).isPresent();
        assertThat(out.get()).contains("小明");
    }

    @Test
    @DisplayName("查不清（网络/权限失败）→ 既不踢也不放行：不把故障当「未订阅」而误踢人")
    void unknownNeitherKicksNorWelcomes() {
        GroupAdminPort admin = mock(GroupAdminPort.class);

        Optional<String> out = gate(admin, ChannelMembershipPort.Membership.UNKNOWN)
                .onMemberJoined(JOINED);

        verify(admin, never()).kick(anyLong(), anyLong());
        assertThat(out).isPresent();
        assertThat(out.get()).contains("无法确认");
        assertThat(out.get())
                .as("查不清时不得发欢迎语——那等于默认放行，门禁形同虚设")
                .doesNotContain("小明");
    }

    @Test
    @DisplayName("未配置必订频道 → 直接委派，且一次都不查订阅（省一次 API 调用）")
    void noRequirementDelegatesWithoutQuerying() {
        AtomicInteger queries = new AtomicInteger();
        JoinSubscriptionGate gate = new JoinSubscriptionGate(
                new ChannelSubscriptionCheck(Set.of(REQUIRED)),
                (channel, userId) -> {
                    queries.incrementAndGet();
                    return ChannelMembershipPort.Membership.UNKNOWN;
                },
                mock(GroupAdminPort.class), inner(), Set.of());

        Optional<String> out = gate.onMemberJoined(JOINED);

        assertThat(queries.get()).isZero();
        assertThat(out).isPresent();
        assertThat(out.get()).contains("小明");
    }

    @Test
    @DisplayName("配置错误（必订频道不在白名单）→ 不踢人：我们的配置错不能变成对用户的处罚")
    void misconfiguredRequiredChannelDoesNotKick() {
        GroupAdminPort admin = mock(GroupAdminPort.class);
        JoinSubscriptionGate gate = new JoinSubscriptionGate(
                new ChannelSubscriptionCheck(Set.of("@other")),   // 白名单不含 REQUIRED
                (channel, userId) -> ChannelMembershipPort.Membership.SUBSCRIBED,
                admin, inner(), Set.of(REQUIRED));

        Optional<String> out = gate.onMemberJoined(JOINED);

        verify(admin, never()).kick(anyLong(), anyLong());
        assertThat(out).isPresent();
        assertThat(out.get()).contains("配置");
    }
}
