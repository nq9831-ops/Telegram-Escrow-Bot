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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 持久化 V2（V2__init_moderation.sql）的真实迁移测试。
 *
 * <p>与 {@code EscrowOrderStoreTest} 同一手法：真实 H2 + 真实 Flyway 迁移 + 真实 JPA——
 * 这一层的错误形态是"列名对不上 / 迁移没跑 / 默认值丢失"，mock 全测不出来。
 * 覆盖四个新端口：警告累计（GM-03）、违禁词存取（在线管理）、messageId 映射（原地编辑）、
 * 用户偏好（通知模式）。
 */
@SpringBootTest(classes = com.tg.escrow.escrow.EscrowPersistenceTestConfig.class)
class ModerationPersistenceTest {

    @Autowired
    private TradeMessageMapPort messageMap;

    @Autowired
    private UserPreferencePort prefs;

    @Test
    @DisplayName("messageId 映射：同交易更新覆盖，读回准确（原地编辑的持久化前提）")
    void tradeMessageMapRoundTrip() {
        messageMap.save(4242L, 100L, 777L);
        messageMap.save(4242L, 100L, 888L); // 同交易后续编辑 → 覆盖

        assertThat(messageMap.find(4242L)).hasValueSatisfying(m -> {
            assertThat(m.chatId()).isEqualTo(100L);
            assertThat(m.messageId()).isEqualTo(888L);
        });
        assertThat(messageMap.find(9L)).isEmpty();
    }

    @Test
    @DisplayName("用户偏好：默认 all、可改 important（按用户隔离）")
    void userPrefsDefaultAll() {
        assertThat(prefs.noticeModeOf(77L)).isEqualTo("all");

        prefs.setNoticeMode(77L, "important");
        assertThat(prefs.noticeModeOf(77L)).isEqualTo("important");
        assertThat(prefs.noticeModeOf(78L)).isEqualTo("all"); // 其它用户不受影响
    }

    @Test
    @DisplayName("ET-80：榜单可见性默认不公开（未登记用户）")
    void rankVisibilityDefaultsToPrivate() {
        UserPreferencePort.RankVisibility v = prefs.rankVisibilityOf(3001L);

        assertThat(v.publicProfile()).isFalse();
        assertThat(v.username()).isNull();
    }

    @Test
    @DisplayName("ET-80：setRankPublic(true) 后回读到公开与 @username；关闭后清除用户名")
    void rankPublicityRoundTrip() {
        prefs.setRankPublic(3002L, true, "alpha_trader");
        UserPreferencePort.RankVisibility open = prefs.rankVisibilityOf(3002L);
        assertThat(open.publicProfile()).isTrue();
        assertThat(open.username()).isEqualTo("alpha_trader");

        // 关闭：不公开且不留旧 @username（避免日后重开展示过期名字）。
        prefs.setRankPublic(3002L, false, null);
        UserPreferencePort.RankVisibility closed = prefs.rankVisibilityOf(3002L);
        assertThat(closed.publicProfile()).isFalse();
        assertThat(closed.username()).isNull();
    }
}
