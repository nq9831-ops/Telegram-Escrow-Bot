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
package com.tg.escrow.moderation;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

/**
 * 持久化 V2 的 JPA 端口适配（与 {@code JpaEscrowOrderStore} 同一手法：仓储内聚、端口对外）。
 * 三个 package-private 适配同文件收纳——它们是同一迁移的四壁，拆开只会散落。
 *
 * <p><b>时钟一律注入</b>：本文件内不接受 {@code Instant.now()} 直接读系统时钟——
 * 项目其余部分都把 {@link Clock} 由构造注入（可测、且同一路径内时刻一致），
 * 这里若破例，时间戳就无法在测试里钉住，行为也会随部署机器漂移。
 */
final class JpaModerationAdapters {

    private JpaModerationAdapters() {
    }

    static final class JpaTradeMessageMapPort implements TradeMessageMapPort {
        private final TradeMessageRepo repo;
        private final Clock clock;

        JpaTradeMessageMapPort(TradeMessageRepo repo, Clock clock) {
            this.repo = repo;
            this.clock = clock;
        }

        @Override
        public void save(long tradeId, long chatId, long messageId) {
            TradeMessageRecord r = repo.findById(tradeId).orElseGet(() -> new TradeMessageRecord(tradeId, chatId, messageId));
            r.chatId = chatId;
            r.messageId = messageId;
            r.updatedAt = clock.instant();
            repo.save(r);
        }

        @Override
        public Optional<MessageRef> find(long tradeId) {
            return repo.findById(tradeId).map(r -> new MessageRef(r.chatId, r.messageId));
        }
    }

    static final class JpaUserPreferencePort implements UserPreferencePort {
        private final UserPrefRepo repo;
        private final Clock clock;

        JpaUserPreferencePort(UserPrefRepo repo, Clock clock) {
            this.repo = repo;
            this.clock = clock;
        }

        @Override
        public String noticeModeOf(long userId) {
            return repo.findById(userId).map(r -> r.noticeMode).orElse("all");
        }

        @Override
        public void setNoticeMode(long userId, String mode) {
            UserPrefRecord r = repo.findById(userId).orElseGet(() -> new UserPrefRecord(userId, mode));
            r.noticeMode = mode;
            r.updatedAt = clock.instant();
            repo.save(r);
        }

        @Override
        public RankVisibility rankVisibilityOf(long userId) {
            // 未登记 / rankPublic=false 恒为不公开：默认脱敏，不因缺行而暴露。
            return repo.findById(userId)
                    .filter(rec -> rec.rankPublic)
                    .map(rec -> new UserPreferencePort.RankVisibility(true, rec.publicUsername))
                    .orElseGet(() -> new UserPreferencePort.RankVisibility(false, null));
        }

        @Override
        public void setRankPublic(long userId, boolean publicProfile, String username) {
            // 与 setNoticeMode 同一「存在则更新、否则新建」路径，保证幂等覆盖。
            UserPrefRecord r = repo.findById(userId)
                    .orElseGet(() -> new UserPrefRecord(userId, "all"));
            r.rankPublic = publicProfile;
            // 关闭时不留旧 @username（避免日后再开却展示过期名字）；开启则落规范化（去空白）后的值。
            r.publicUsername = publicProfile && username != null && !username.isBlank()
                    ? username.trim()
                    : null;
            r.updatedAt = clock.instant();
            repo.save(r);
        }
    }
}
