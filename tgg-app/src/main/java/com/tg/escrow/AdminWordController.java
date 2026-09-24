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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Admin API（S6）：违禁词在线管理（GM-06）——实时增删改、热生效无需重启。
 *
 * <h2>写路径原子性（审查修复）</h2>
 * <p>{@code save} 先落库再热编译；<b>编译失败（坏正则）立即回滚持久层</b>——
 * 毒条目不得留在库里（否则会持续毒化后续 reload 甚至炸启动）。失败返回 <b>HTTP 400</b>
 * （与本注释契约一致）；{@code disable} 同样如实回报 reload 结果，不无条件 ok=true。
 *
 * <p><b>⚠️ 本端点当前无鉴权</b>——部署必须配鉴权且仅内网暴露（线上清单 C1）。
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

    /** 添加/启用一条违禁词并热生效；坏正则 → 400 且回滚（词库保持旧值）。 */
    @PostMapping("/save")
    public ResponseEntity<Map<String, Object>> save(@RequestParam("guild") long guildId,
                                                    @RequestParam("word") String word,
                                                    @RequestParam(value = "regex", defaultValue = "false") boolean regex) {
        store.save(guildId, word, regex);
        if (!reloadAll(guildId)) {
            // 毒条目回滚：不得留在库里持续毒化后续 reload
            store.disable(guildId, word);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("ok", false, "error", "词库编译失败（坏正则？），已回滚并保留原词库"));
        }
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /** 停用一条违禁词并热生效；reload 失败如实报错。 */
    @PostMapping("/disable")
    public ResponseEntity<Map<String, Object>> disable(@RequestParam("guild") long guildId,
                                                       @RequestParam("word") String word) {
        store.disable(guildId, word);
        if (!reloadAll(guildId)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("ok", false, "error", "停用后词库编译失败——可能存在毒条目，请核查词库"));
        }
        return ResponseEntity.ok(Map.of("ok", true));
    }

    private boolean reloadAll(long guildId) {
        List<BannedWordStore.Entry> enabled = store.enabledOf(guildId);
        List<String> exact = enabled.stream().filter(e -> !e.regex()).map(BannedWordStore.Entry::word).toList();
        List<String> regex = enabled.stream().filter(BannedWordStore.Entry::regex).map(BannedWordStore.Entry::word).toList();
        return registry.reload(guildId, exact, regex);
    }
}
