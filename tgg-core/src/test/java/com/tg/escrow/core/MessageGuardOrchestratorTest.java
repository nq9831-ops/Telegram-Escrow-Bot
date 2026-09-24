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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 内容安全编排的行为固定测试（Wave 1）。
 *
 * <h2>它守的是什么</h2>
 * <p>三个守卫（链接/媒体/限流）此前各自有单测却无人调用。本类钉住的是<b>串联语义</b>：
 * 首个命中即决胜、顺序确定；以及最要紧的一侧——<b>不该拦的绝不拦</b>
 * （白名单链接、白名单媒体、阈值内消息都必须放行），因为误杀的代价是正常用户被打扰甚至被删消息。
 */
class MessageGuardOrchestratorTest {

    private static final long CHAT = -1001234567890L;
    private static final long USER = 2002L;
    private static final long MSG = 77L;
    private static final Instant NOW = Instant.parse("2026-09-24T10:00:00Z");
    private static final Clock FIXED = Clock.fixed(NOW, ZoneOffset.UTC);

    private static MessageGuardOrchestrator guard(int userThreshold) {
        return new MessageGuardOrchestrator(
                new LinkFilter(List.of("good.example"), List.of("t.cn")),
                new MediaFilter(Set.of("jpg", "pdf"), Set.of("image/jpeg", "application/pdf")),
                new RateLimiter(new RateLimitPolicy(Duration.ofMinutes(1), userThreshold, 100, 1000)),
                FIXED);
    }

    private static IncomingMessage text(String body) {
        return IncomingMessage.text(CHAT, USER, MSG, body);
    }

    // ── 链接 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("非白名单链接 → 拦下，理由含域名")
    void suspiciousLinkBlocked() {
        MessageGuardOrchestrator.Decision d = guard(10).inspect(text("点这里 http://evil.example/x 领奖"));

        assertThat(d.blocked()).isTrue();
        assertThat(d.reason()).contains("evil.example");
    }

    @Test
    @DisplayName("白名单链接 → 放行（不能误杀正常用户）")
    void whitelistedLinkPasses() {
        assertThat(guard(10).inspect(text("见 http://good.example/doc")).blocked()).isFalse();
    }

    @Test
    @DisplayName("纯文本无链接 → 放行")
    void plainTextPasses() {
        assertThat(guard(10).inspect(text("大家早上好")).blocked()).isFalse();
    }

    /** 白名单未配置、但短链表已配置的编排器（模拟默认部署：只有短链表有值）。 */
    private static MessageGuardOrchestrator unconfigured() {
        return new MessageGuardOrchestrator(
                new LinkFilter(List.of(), List.of("t.cn")),
                new MediaFilter(Set.of(), Set.of()),
                new RateLimiter(new RateLimitPolicy(Duration.ofMinutes(1), 100, 100, 100)),
                FIXED);
    }

    @Test
    @DisplayName("【缺陷复现】白名单未配置时普通链接不得算违规——否则默认部署会删光带链接的消息")
    void emptyWhitelistDoesNotFlagOrdinaryLinks() {
        MessageGuardOrchestrator.Decision d = unconfigured().inspect(text("见 http://good.example/doc"));

        assertThat(d.blocked())
                .as("未配置白名单 = 没有判定依据，不等于『所有域名都被禁』")
                .isFalse();
    }

    @Test
    @DisplayName("白名单未配置时仍拦短链（短链隐藏真实目标，不需要白名单依据）")
    void emptyWhitelistStillBlocksShorteners() {
        MessageGuardOrchestrator.Decision d = unconfigured().inspect(text("短链 http://t.cn/abc"));

        assertThat(d.blocked()).isTrue();
        assertThat(d.reason()).contains("t.cn");
    }

    // ── 媒体 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("非白名单扩展名 → 拦下")
    void disallowedMediaBlocked() {
        IncomingMessage msg = new IncomingMessage(CHAT, USER, MSG, null,
                IncomingMessage.MediaKind.DOCUMENT, "payload.exe", "application/octet-stream");

        MessageGuardOrchestrator.Decision d = guard(10).inspect(msg);

        assertThat(d.blocked()).isTrue();
        assertThat(d.reason()).contains("媒体");
    }

    @Test
    @DisplayName("白名单媒体 → 放行")
    void allowedMediaPasses() {
        IncomingMessage msg = new IncomingMessage(CHAT, USER, MSG, null,
                IncomingMessage.MediaKind.DOCUMENT, "note.pdf", "application/pdf");

        assertThat(guard(10).inspect(msg).blocked()).isFalse();
    }

    @Test
    @DisplayName("图片消息（无文件名/类型）→ 不做媒体判定，不因空值误杀")
    void photoWithoutFileInfoPasses() {
        IncomingMessage msg = new IncomingMessage(CHAT, USER, MSG, null,
                IncomingMessage.MediaKind.PHOTO, null, null);

        assertThat(guard(10).inspect(msg).blocked())
                .as("图片在 Telegram 侧通常无文件名/MIME——不能拿空值当违规")
                .isFalse();
    }

    // ── 限流 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("阈值内 → 放行；超阈值 → 拦下并说明是哪一层")
    void rateLimitBlocksOnlyAfterThreshold() {
        MessageGuardOrchestrator guard = guard(2);

        assertThat(guard.inspect(text("一")).blocked()).isFalse();
        assertThat(guard.inspect(text("二")).blocked()).isFalse();

        MessageGuardOrchestrator.Decision third = guard.inspect(text("三"));

        assertThat(third.blocked()).isTrue();
        assertThat(third.reason()).contains("限流");
    }

    // ── 顺序 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("首个命中即决胜：链接问题优先于限流")
    void firstHitWins() {
        MessageGuardOrchestrator guard = guard(1);   // 阈值 1，第二条必超限

        MessageGuardOrchestrator.Decision d = guard.inspect(text("看 http://evil.example/x"));

        assertThat(d.reason())
                .as("链接命中在前，理由应是链接而不是限流")
                .contains("evil.example");
    }

    // ── fail-fast ───────────────────────────────────────────────────────

    @Test
    @DisplayName("依赖缺失 → 构造即抛")
    void missingDepsFailFast() {
        LinkFilter links = new LinkFilter(List.of(), List.of());
        MediaFilter media = new MediaFilter(Set.of(), Set.of());
        RateLimiter rate = new RateLimiter(new RateLimitPolicy(Duration.ofMinutes(1), 5, 10, 100));

        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> new MessageGuardOrchestrator(null, media, rate, FIXED)))
                .isInstanceOf(TggException.class);
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> new MessageGuardOrchestrator(links, null, rate, FIXED)))
                .isInstanceOf(TggException.class);
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> new MessageGuardOrchestrator(links, media, null, FIXED)))
                .isInstanceOf(TggException.class);
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> new MessageGuardOrchestrator(links, media, rate, null)))
                .isInstanceOf(TggException.class);
    }
}
