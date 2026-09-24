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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Admin API（S6）：违禁词在线管理（GM-06，tg-group-guard 式实时增删改）。
 *
 * <p>写路径：{@code BannedWordStore} 持久化 → {@link BannedWordRegistry#reload} 热生效——
 * <b>即时生效无需重启</b>；reload 失败（坏正则）返回 400 且词库保持旧值（fail-safe）。
 *
 * <p><b>运行期（HTTP 暴露/鉴权）未经本地验证</b>——部署时必须配鉴权与仅内网暴露
 * （管理端点不应对公网裸奔），属线上阶段验收项。
 */
@RestController
@RequestMapping("/admin/words")
public class AdminWordController {

    private final BannedWordStore store;
    private final BannedWordRegistry registry;

    public AdminWordController(BannedWordStore store, BannedWordRegistry registry) {
        if (store == null || registry == null) {
            throw new com.tg.escrow.common.TggException("Admin API：存储与注册表均不可为空");
        }
        this.store = store;
        this.registry = registry;
    }

    /** 查看该群启用中的违禁词。 */
    @GetMapping
    public List<BannedWordStore.Entry> list(@RequestParam("guild") long guildId) {
        return store.enabledOf(guildId);
    }

    /** 添加/启用一条违禁词并热生效；坏正则返回 400（词库保持旧值）。 */
    @PostMapping("/save")
    public Map<String, Object> save(@RequestParam("guild") long guildId,
                                   @RequestParam("word") String word,
                                   @RequestParam(value = "regex", defaultValue = "false") boolean regex) {
        store.save(guildId, word, regex);
        if (!reloadAll(guildId)) {
            return Map.of("ok", false, "error", "词库编译失败（坏正则？），已保留原词库");
        }
        return Map.of("ok", true);
    }

    /** 停用一条违禁词并热生效。 */
    @PostMapping("/disable")
    public Map<String, Object> disable(@RequestParam("guild") long guildId,
                                      @RequestParam("word") String word) {
        store.disable(guildId, word);
        reloadAll(guildId);
        return Map.of("ok", true);
    }

    private boolean reloadAll(long guildId) {
        List<BannedWordStore.Entry> enabled = store.enabledOf(guildId);
        List<String> exact = enabled.stream().filter(e -> !e.regex()).map(BannedWordStore.Entry::word).toList();
        List<String> regex = enabled.stream().filter(BannedWordStore.Entry::regex).map(BannedWordStore.Entry::word).toList();
        return registry.reload(exact, regex);
    }
}
