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

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

/*
 * 持久化 V2 的实体与仓储（package-private 顶级类型：对外契约在 XxxPort 端口）。
 * 刻意不做嵌套类——Spring Data 的仓储扫描与 JPA 实体发现都只认顶级类型，
 * 嵌套会导致"找不到 repo Bean"（已实测踩坑）。表结构由 Flyway 管理（V2__init_moderation.sql）。
 */

/** 警告累计（warnings），GM-03。 */
@Entity
@Table(name = "warnings")
class WarningRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;
    @Column(name = "guild_id", nullable = false)
    long guildId;
    @Column(name = "user_id", nullable = false)
    long userId;
    @Column(name = "warn_count", nullable = false)
    int warnCount;
    @Column(name = "updated_at", nullable = false)
    Instant updatedAt;

    protected WarningRecord() {
    }

    WarningRecord(long guildId, long userId) {
        this.guildId = guildId;
        this.userId = userId;
        this.warnCount = 1;
        this.updatedAt = Instant.EPOCH;
    }
}

/** 违禁词（banned_words），GM-06 在线管理。 */
@Entity
@Table(name = "banned_words")
class BannedWordEntry {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;
    @Column(name = "guild_id", nullable = false)
    long guildId;
    @Column(name = "word", nullable = false, length = 512)
    String word;
    @Column(name = "is_regex", nullable = false)
    boolean regex;
    @Column(name = "enabled", nullable = false)
    boolean enabled;

    protected BannedWordEntry() {
    }

    BannedWordEntry(long guildId, String word, boolean regex) {
        this.guildId = guildId;
        this.word = word;
        this.regex = regex;
        this.enabled = true;
    }
}

/** 交易消息映射（trade_message_map），原地编辑的持久化前提。 */
@Entity
@Table(name = "trade_message_map")
class TradeMessageRecord {
    @Id
    @Column(name = "trade_id", nullable = false)
    long tradeId;
    @Column(name = "chat_id", nullable = false)
    long chatId;
    @Column(name = "message_id", nullable = false)
    long messageId;
    @Column(name = "updated_at", nullable = false)
    Instant updatedAt;

    protected TradeMessageRecord() {
    }

    TradeMessageRecord(long tradeId, long chatId, long messageId) {
        this.tradeId = tradeId;
        this.chatId = chatId;
        this.messageId = messageId;
        this.updatedAt = Instant.EPOCH;
    }
}

/** 用户偏好（user_prefs），通知模式。 */
@Entity
@Table(name = "user_prefs")
class UserPrefRecord {
    @Id
    @Column(name = "user_id", nullable = false)
    long userId;
    @Column(name = "notice_mode", nullable = false, length = 16)
    String noticeMode;
    @Column(name = "updated_at", nullable = false)
    Instant updatedAt;

    protected UserPrefRecord() {
    }

    UserPrefRecord(long userId, String noticeMode) {
        this.userId = userId;
        this.noticeMode = noticeMode;
        this.updatedAt = Instant.EPOCH;
    }
}

interface WarningRepo extends JpaRepository<WarningRecord, Long> {
    WarningRecord findByGuildIdAndUserId(long guildId, long userId);
}

interface BannedWordRepo extends JpaRepository<BannedWordEntry, Long> {
    List<BannedWordEntry> findByGuildIdAndEnabledTrue(long guildId);

    BannedWordEntry findByGuildIdAndWord(long guildId, String word);
}

interface TradeMessageRepo extends JpaRepository<TradeMessageRecord, Long> {
}

interface UserPrefRepo extends JpaRepository<UserPrefRecord, Long> {
}
