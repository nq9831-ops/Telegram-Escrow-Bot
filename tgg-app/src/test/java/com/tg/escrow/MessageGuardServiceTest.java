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
import com.tg.escrow.core.GroupAdminPort;
import com.tg.escrow.core.IncomingMessage;
import com.tg.escrow.core.LinkFilter;
import com.tg.escrow.core.MediaFilter;
import com.tg.escrow.core.MessageGuardOrchestrator;
import com.tg.escrow.core.RateLimitPolicy;
import com.tg.escrow.core.RateLimiter;
import com.tg.escrow.moderation.WarningPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 内容安全处置的行为固定测试（Wave 3）。
 *
 * <h2>它守的是什么</h2>
 * <p>用<b>真实</b>编排器 + 记录型 fake 端口（不 mock 自家中间层），钉住三件事：
 * ① 命中时消息<b>真的被删</b>、警告<b>真的被记</b>；② 未命中时端口<b>零调用</b>（不误伤）；
 * ③ 删除失败<b>不静默</b>——吞掉会让管理员以为消息已清理。
 */
class MessageGuardServiceTest {

    private static final long CHAT = -1001234567890L;
    private static final long USER = 2002L;
    private static final long MSG = 77L;
    private static final Instant NOW = Instant.parse("2026-09-24T10:00:00Z");

    /** 记录型端口：把「有没有真的动手」变成可断言的物理事实。 */
    static final class RecordingAdmin implements GroupAdminPort {
        final List<String> calls = new ArrayList<>();
        RuntimeException failOnDelete;

        @Override
        public void kick(long guildId, long userId) {
            calls.add("kick");
        }

        @Override
        public void ban(long guildId, long userId) {
            calls.add("ban");
        }

        @Override
        public void mute(long guildId, long userId, Duration duration) {
            calls.add("mute");
        }

        @Override
        public void deleteMessage(long guildId, long messageId) {
            if (failOnDelete != null) {
                throw failOnDelete;
            }
            calls.add("del:" + guildId + ":" + messageId);
        }
    }

    static final class RecordingWarnings implements WarningPort {
        final List<String> calls = new ArrayList<>();

        @Override
        public int warn(long guildId, long userId) {
            calls.add("warn:" + guildId + ":" + userId);
            return calls.size();
        }

        @Override
        public int countOf(long guildId, long userId) {
            return 0;
        }

        @Override
        public void clear(long guildId, long userId) {
        }
    }

    /** 链接白名单只放行 good.example；限流阈值给足以免干扰本类用例。 */
    private static MessageGuardService service(RecordingAdmin admin, RecordingWarnings warnings) {
        MessageGuardOrchestrator guard = new MessageGuardOrchestrator(
                new LinkFilter(List.of("good.example"), List.of("t.cn")),
                new MediaFilter(Set.of("pdf"), Set.of("application/pdf")),
                new RateLimiter(new RateLimitPolicy(Duration.ofMinutes(1), 100, 100, 100)),
                Clock.fixed(NOW, ZoneOffset.UTC));
        return new MessageGuardService(guard, admin, warnings);
    }

    private static IncomingMessage message(String text) {
        return IncomingMessage.text(CHAT, USER, MSG, text);
    }

    @Test
    @DisplayName("【接线证据】可疑链接 → 消息真的被删、警告真的被记，回执说明理由")
    void suspiciousLinkTriggersRealDisposal() {
        RecordingAdmin admin = new RecordingAdmin();
        RecordingWarnings warnings = new RecordingWarnings();

        Optional<String> reply = service(admin, warnings).screen(message("点这里 http://evil.example/x 领奖"));

        assertThat(admin.calls)
                .as("判定命中必须真的调用删消息——否则过滤规则只是装饰")
                .containsExactly("del:" + CHAT + ":" + MSG);
        assertThat(warnings.calls).containsExactly("warn:" + CHAT + ":" + USER);
        assertThat(reply).isPresent();
        assertThat(reply.get()).contains("evil.example");
    }

    @Test
    @DisplayName("白名单链接 → 放行，端口【零调用】（不误伤正常用户）")
    void whitelistedLinkIsNotTouched() {
        RecordingAdmin admin = new RecordingAdmin();
        RecordingWarnings warnings = new RecordingWarnings();

        Optional<String> reply = service(admin, warnings).screen(message("见 http://good.example/doc"));

        assertThat(reply).isEmpty();
        assertThat(admin.calls).isEmpty();
        assertThat(warnings.calls).isEmpty();
    }

    @Test
    @DisplayName("【不静默】删消息失败 → 抛异常，且不假装记过警告")
    void deleteFailureIsNotSilent() {
        RecordingAdmin admin = new RecordingAdmin();
        admin.failOnDelete = new TggException("Telegram 拒绝删除");
        RecordingWarnings warnings = new RecordingWarnings();

        assertThatThrownBy(() -> service(admin, warnings).screen(message("http://evil.example/x")))
                .as("吞掉处置失败会让人以为消息已被清理")
                .isInstanceOf(TggException.class);
        assertThat(warnings.calls)
                .as("删不掉就不该假装处置过——先删后记，删失败即中止")
                .isEmpty();
    }

    @Test
    @DisplayName("构造：依赖缺失或消息为空 → 抛")
    void failsClosedOnMissingDeps() {
        RecordingAdmin admin = new RecordingAdmin();
        RecordingWarnings warnings = new RecordingWarnings();

        assertThatThrownBy(() -> new MessageGuardService(null, admin, warnings))
                .isInstanceOf(TggException.class);
        assertThatThrownBy(() -> new MessageGuardService(
                new MessageGuardOrchestrator(new LinkFilter(List.of(), List.of()),
                        new MediaFilter(Set.of(), Set.of()),
                        new RateLimiter(new RateLimitPolicy(Duration.ofMinutes(1), 5, 5, 5)),
                        Clock.fixed(NOW, ZoneOffset.UTC)),
                null, warnings))
                .isInstanceOf(TggException.class);
        assertThatThrownBy(() -> service(admin, warnings).screen(null))
                .isInstanceOf(TggException.class);
    }
}
