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

import java.time.Clock;
import java.util.Optional;

/**
 * 内容安全编排（Wave 1）——把三个此前零引用的守卫串成一条判定路径。
 *
 * <h2>顺序定死：链接 → 媒体 → 限流</h2>
 * <p><b>首个命中即决胜</b>，理由只报第一个。顺序不是随意的：
 * 链接与媒体是「这条消息本身有问题」（对群即时可见的危害），限流是「这个人发太多了」
 * （行为问题）。先报内容问题，管理员一眼知道该看什么；若先报限流，一条带钓鱼链接的消息
 * 会被描述成"发得太快"，掩盖真正的危险。
 *
 * <h2>两侧都重要</h2>
 * <p>拦得住是功能，<b>不误拦是底线</b>——白名单链接、白名单媒体、阈值内的正常发言都必须放行。
 * 误杀的代价是正常用户被打扰甚至消息被删，比漏拦更伤信任。
 *
 * <h2>不做的事</h2>
 * <p>本类只<b>判定</b>，不执行处置（删消息/记警告由调用方做）。判定与执行分开，
 * 使判定可在纯本地被穷举测试，而执行（碰 Telegram API）只在一个地方发生。
 *
 * <p>无状态、可并发调用；时钟由构造注入使限流窗口判定可测。
 */
public final class MessageGuardOrchestrator {

    /**
     * 判定结果。
     *
     * @param blocked 是否应处置
     * @param reason  处置理由（放行时为空串；拦下时不得为空白——空理由等于没记原因）
     */
    public record Decision(boolean blocked, String reason) {

        public Decision {
            if (reason == null) {
                throw new TggException("内容安全：判定理由不可为空");
            }
            if (blocked && reason.isBlank()) {
                throw new TggException("内容安全：拦下时必须给出理由（空理由等于没记原因）");
            }
        }

        /** 放行。 */
        public static Decision allow() {
            return new Decision(false, "");
        }

        /** 拦下并说明理由。 */
        public static Decision block(String reason) {
            return new Decision(true, reason);
        }
    }

    private final LinkFilter links;
    private final MediaFilter media;
    private final RateLimiter rate;
    private final Clock clock;

    public MessageGuardOrchestrator(LinkFilter links, MediaFilter media, RateLimiter rate, Clock clock) {
        if (links == null || media == null || rate == null) {
            throw new TggException("内容安全：链接过滤/媒体过滤/限流三者均不可为空");
        }
        if (clock == null) {
            throw new TggException("内容安全：时钟不可为空");
        }
        this.links = links;
        this.media = media;
        this.rate = rate;
        this.clock = clock;
    }

    /**
     * 判定一条消息是否应被处置。
     *
     * @param message 入站消息（不得为 {@code null}）
     * @return 判定结果（首个命中即决胜）
     */
    public Decision inspect(IncomingMessage message) {
        if (message == null) {
            throw new TggException("内容安全：消息不可为空");
        }

        // 1) 链接：可疑（非白名单 / 短链）即拦
        if (message.text() != null && !message.text().isBlank()) {
            Optional<LinkMatch> hit = links.firstLink(message.text());
            if (hit.isPresent() && isSuspicious(hit.get())) {
                return Decision.block("链接不可信：" + hit.get().host());
            }
        }

        // 2) 媒体：仅在拿到文件信息时判定——不拿空值当违规，避免误杀图片消息
        if (message.hasMedia() && message.hasFileInfo()
                && !media.isAllowed(message.fileName(), message.mimeType())) {
            return Decision.block("媒体类型不被允许：" + describeMedia(message));
        }

        // 3) 限流：任一层超限即拦，并说明是哪一层
        RateLimitDecision limit = rate.check(message.userId(), message.chatId(), clock.instant());
        if (!limit.allowed()) {
            return Decision.block("触发限流（" + limit.layer() + "）");
        }

        return Decision.allow();
    }

    /**
     * 该链接是否该拦。
     *
     * <p><b>白名单未配置时只拦短链</b>：未配置 = 没有判定依据，<b>不</b>等于"所有域名都被禁"。
     * 若直接用 {@link LinkMatch#suspicious()}（= 非白名单即可疑），空白名单会让每一条含链接的
     * 消息都被判违规——默认部署下就是一场误删。短链则无需白名单依据：它本来就隐藏真实目标。
     */
    private boolean isSuspicious(LinkMatch match) {
        if (!links.hasAllowedDomains()) {
            return match.shortener();
        }
        return match.suspicious();
    }

    private static String describeMedia(IncomingMessage message) {        if (message.fileName() != null && !message.fileName().isBlank()) {
            return message.fileName();
        }
        return message.mimeType() == null ? message.mediaKind().name() : message.mimeType();
    }
}
