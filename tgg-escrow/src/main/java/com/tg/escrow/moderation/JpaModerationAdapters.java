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

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 持久化 V2 的 JPA 端口适配（与 {@code JpaEscrowOrderStore} 同一手法：仓储内聚、端口对外）。
 * 三个 package-private 适配同文件收纳——它们是同一迁移的四壁，拆开只会散落。
 */
final class JpaModerationAdapters {

    private JpaModerationAdapters() {
    }

    static final class JpaWarningPort implements WarningPort {
        private final WarningRepo repo;

        JpaWarningPort(WarningRepo repo) {
            this.repo = repo;
        }

        @Override
        public int warn(long guildId, long userId) {
            WarningRecord r = repo.findByGuildIdAndUserId(guildId, userId);
            if (r == null) {
                r = repo.save(new WarningRecord(guildId, userId));
            } else {
                r.warnCount++;
                r.updatedAt = Instant.now();
            }
            return repo.save(r).warnCount;
        }

        @Override
        public int countOf(long guildId, long userId) {
            WarningRecord r = repo.findByGuildIdAndUserId(guildId, userId);
            return r == null ? 0 : r.warnCount;
        }

        @Override
        public void clear(long guildId, long userId) {
            WarningRecord r = repo.findByGuildIdAndUserId(guildId, userId);
            if (r != null) {
                repo.delete(r);
            }
        }
    }

    static final class JpaBannedWordStore implements BannedWordStore {
        private final BannedWordRepo repo;

        JpaBannedWordStore(BannedWordRepo repo) {
            this.repo = repo;
        }

        @Override
        public void save(long guildId, String word, boolean regex) {
            BannedWordEntry e = repo.findByGuildIdAndWord(guildId, word);
            if (e == null) {
                repo.save(new BannedWordEntry(guildId, word, regex));
            } else {
                e.regex = regex;
                e.enabled = true;
                repo.save(e);
            }
        }

        @Override
        public void disable(long guildId, String word) {
            BannedWordEntry e = repo.findByGuildIdAndWord(guildId, word);
            if (e != null) {
                e.enabled = false;
                repo.save(e);
            }
        }

        @Override
        public List<Entry> enabledOf(long guildId) {
            return repo.findByGuildIdAndEnabledTrue(guildId).stream()
                    .map(e -> new Entry(e.word, e.regex))
                    .toList();
        }
    }

    static final class JpaTradeMessageMapPort implements TradeMessageMapPort {
        private final TradeMessageRepo repo;

        JpaTradeMessageMapPort(TradeMessageRepo repo) {
            this.repo = repo;
        }

        @Override
        public void save(long tradeId, long chatId, long messageId) {
            TradeMessageRecord r = repo.findById(tradeId).orElseGet(() -> new TradeMessageRecord(tradeId, chatId, messageId));
            r.chatId = chatId;
            r.messageId = messageId;
            r.updatedAt = Instant.now();
            repo.save(r);
        }

        @Override
        public Optional<MessageRef> find(long tradeId) {
            return repo.findById(tradeId).map(r -> new MessageRef(r.chatId, r.messageId));
        }
    }

    static final class JpaUserPreferencePort implements UserPreferencePort {
        private final UserPrefRepo repo;

        JpaUserPreferencePort(UserPrefRepo repo) {
            this.repo = repo;
        }

        @Override
        public String noticeModeOf(long userId) {
            return repo.findById(userId).map(r -> r.noticeMode).orElse("all");
        }

        @Override
        public void setNoticeMode(long userId, String mode) {
            UserPrefRecord r = repo.findById(userId).orElseGet(() -> new UserPrefRecord(userId, mode));
            r.noticeMode = mode;
            r.updatedAt = Instant.now();
            repo.save(r);
        }
    }
}
