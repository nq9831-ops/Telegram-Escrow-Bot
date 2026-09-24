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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 违禁词热更新（GM-06 在线管理）的行为固定测试。
 *
 * <p>两条硬规则：①<b>热更新即时生效</b>（新词上线不重启）；②<b>非法新词库保留旧词库</b>
 * （fail-safe——一次错误配置不得让过滤失效）。
 */
class BannedWordRegistryTest {

    @Test
    @DisplayName("热更新即时生效：reload 后新词可命中")
    void reloadTakesEffect() {
        BannedWordRegistry r = new BannedWordRegistry(List.of("旧词"), List.of());

        assertThat(r.firstMatch("这里有旧词")).isPresent();

        assertThat(r.reload(List.of("新词"), List.of())).isTrue();
        assertThat(r.firstMatch("这里有旧词")).isEmpty();
        assertThat(r.firstMatch("这里有新词")).isPresent();
    }

    @Test
    @DisplayName("非法新词库（坏正则）→ reload 失败且保留旧词库（fail-safe）")
    void invalidReloadKeepsOld() {
        BannedWordRegistry r = new BannedWordRegistry(List.of("旧词"), List.of());

        assertThat(r.reload(List.of("新词"), List.of("[未闭合"))).isFalse();

        assertThat(r.firstMatch("这里有旧词")).isPresent(); // 旧词库仍在岗
        assertThat(r.firstMatch("这里有新词")).isEmpty();
    }

    @Test
    @DisplayName("初始词库非法 → 构造期 fail-fast")
    void invalidInitialFailsFast() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new BannedWordRegistry(List.of(), List.of("[未闭合")))
                .isInstanceOf(TggException.class);
    }
}
