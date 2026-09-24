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

import com.tg.escrow.core.BannedWordRegistry;
import com.tg.escrow.moderation.BannedWordStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 违禁词管理端点（GM-06 在线管理）的行为固定测试。
 *
 * <p>审查发现的三条缺陷在此钉住：<b>坏正则 → HTTP 400 且持久层回滚</b>（毒条目不得入库）、
 * <b>disable 后 reload 失败必须如实报错</b>（不无条件 ok=true）、成功路径热生效。
 * 内存 fake store——纯逻辑单测不起 web 环境（HTTP 鉴权属线上清单 C1）。
 */
class AdminWordControllerTest {

    /** 内存版 store，记录 disable 调用以验证回滚。 */
    private static class FakeStore implements BannedWordStore {
        final List<Entry> entries = new ArrayList<>();
        final List<String> disabled = new ArrayList<>();

        @Override
        public void save(long guildId, String word, boolean regex) {
            entries.removeIf(e -> e.word().equals(word));
            entries.add(new Entry(word, regex));
        }

        @Override
        public void disable(long guildId, String word) {
            disabled.add(word);
            entries.removeIf(e -> e.word().equals(word));
        }

        @Override
        public List<Entry> enabledOf(long guildId) {
            return List.copyOf(entries);
        }
    }

    private static FakeStore storeWith(String... words) {
        FakeStore s = new FakeStore();
        for (String w : words) {
            s.save(1L, w, false);
        }
        return s;
    }

    @Test
    @DisplayName("正常保存 → 200 且词库热生效")
    void saveSucceeds() {
        FakeStore store = new FakeStore();
        BannedWordRegistry registry = new BannedWordRegistry();
        AdminWordController c = new AdminWordController(store, registry);

        ResponseEntity<Map<String, Object>> resp = c.save(1L, "违禁词", false);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsEntry("ok", true);
        assertThat(registry.firstMatch(1L, "这里有 违禁词")).isPresent();
    }

    @Test
    @DisplayName("坏正则 → HTTP 400 且持久层回滚（毒条目不得留库）")
    void badRegexRolledBackWith400() {
        FakeStore store = storeWith("旧词");
        BannedWordRegistry registry = new BannedWordRegistry();
        registry.reload(1L, List.of("旧词"), List.of());
        AdminWordController c = new AdminWordController(store, registry);

        ResponseEntity<Map<String, Object>> resp = c.save(1L, "[未闭合", true);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(store.disabled).containsExactly("[未闭合"); // 回滚被调用
        assertThat(store.enabledOf(1L)).extracting(BannedWordStore.Entry::word).containsExactly("旧词");
        assertThat(registry.firstMatch(1L, "旧词")).isPresent(); // 旧词库完好
    }

    @Test
    @DisplayName("disable 后 reload 失败 → 如实报错（不无条件 ok=true）")
    void disableReportsReloadFailure() {
        FakeStore store = new FakeStore() {
            @Override
            public List<Entry> enabledOf(long guildId) {
                return List.of(new Entry("[未闭合", true)); // 模拟毒条目残留导致 reload 失败
            }
        };
        BannedWordRegistry registry = new BannedWordRegistry();
        AdminWordController c = new AdminWordController(store, registry);

        ResponseEntity<Map<String, Object>> resp = c.disable(1L, "任意");

        assertThat(resp.getBody()).containsEntry("ok", false);
    }

    @Test
    @DisplayName("list 委托 store")
    void listDelegates() {
        FakeStore store = storeWith("词A");
        AdminWordController c = new AdminWordController(store, new BannedWordRegistry());

        assertThat(c.list(1L)).extracting(BannedWordStore.Entry::word).containsExactly("词A");
    }
}
