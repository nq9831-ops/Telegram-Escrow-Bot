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

import com.tg.escrow.common.TggException;
import com.tg.escrow.core.MemberJoined;
import com.tg.escrow.core.ProtectionMode;
import com.tg.escrow.core.WelcomeTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 入群处理的行为固定测试（Wave 2）。
 *
 * <h2>它守的是什么</h2>
 * <p>用<b>真实</b> {@link ProtectionMode} + 真实 {@link WelcomeTemplate}（不 mock 中间层），
 * 钉住两件事：① 保护模式关闭时确实发出带新人名字的欢迎语；② 开启时确实拦截、
 * <b>且不发欢迎语</b>——向一个本该被拦下的人说"欢迎"是自相矛盾的信号。
 */
class MemberJoinHandlerTest {

    private static final long CHAT = -1001234567890L;

    private static MemberJoined joined(String userName) {
        return new MemberJoined(CHAT, "担保交易群", 4242L, userName);
    }

    private static MemberJoinHandler handler(ProtectionMode mode) {
        return new MemberJoinHandler(mode, new WelcomeTemplate("欢迎 {username} 加入 {group}"));
    }

    @Test
    @DisplayName("保护模式关闭 → 发出欢迎语（含新人名字与群名）")
    void welcomesWhenNotProtected() {
        String out = handler(new ProtectionMode()).onMemberJoined(joined("小明")).orElseThrow();

        assertThat(out).isEqualTo("欢迎 小明 加入 担保交易群");
    }

    @Test
    @DisplayName("保护模式开启 → 拦截，且【不发欢迎语】")
    void rejectsWhenProtected() {
        ProtectionMode mode = new ProtectionMode();
        mode.enable("疑似批量拉人");

        String out = handler(mode).onMemberJoined(joined("小明")).orElseThrow();

        assertThat(out).contains("保护模式").contains("疑似批量拉人");
        assertThat(out)
                .as("向一个本该被拦下的人说『欢迎』是自相矛盾的信号")
                .doesNotContain("欢迎 小明");
    }

    @Test
    @DisplayName("拦截后关闭保护模式 → 恢复欢迎（开关真的生效，不是单向卡死）")
    void welcomeResumesAfterDisable() {
        ProtectionMode mode = new ProtectionMode();
        mode.enable("临时");
        mode.disable();

        assertThat(handler(mode).onMemberJoined(joined("小明")).orElseThrow()).contains("小明");
    }

    @Test
    @DisplayName("显示名/群名缺失 → 不泄漏 {…} 原文（保守处理）")
    void missingNamesDoNotLeakPlaceholders() {
        MemberJoinHandler h = new MemberJoinHandler(
                new ProtectionMode(), new WelcomeTemplate("欢迎 {username} 加入 {group}"));
        MemberJoined anonymous = new MemberJoined(CHAT, null, 4242L, null);

        String out = h.onMemberJoined(anonymous).orElseThrow();

        assertThat(out).doesNotContain("{username}").doesNotContain("{group}");
    }

    @Test
    @DisplayName("构造/入参缺失 → 抛（fail-fast）")
    void failsClosed() {
        assertThatThrownBy(() -> new MemberJoinHandler(null, new WelcomeTemplate("x")))
                .isInstanceOf(TggException.class);
        assertThatThrownBy(() -> new MemberJoinHandler(new ProtectionMode(), null))
                .isInstanceOf(TggException.class);
        assertThatThrownBy(() -> handler(new ProtectionMode()).onMemberJoined(null))
                .isInstanceOf(TggException.class);
    }
}
