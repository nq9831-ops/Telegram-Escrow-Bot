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

import com.tg.escrow.common.TggException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Bot Token 配置（S1 fail-fast 约束）的行为固定测试。
 *
 * <p>缺失/空白 token 必须<b>拒绝启动</b>（fail-fast）——而不是带着空 token 起来后静默不响应
 * （用户看到的是"机器人没反应"，极难排查）。来源函数注入，测试不需要真实环境变量。
 */
class BotTokenConfigTest {

    private static Function<String, String> env(Map<String, String> map) {
        return map::get;
    }

    @Test
    @DisplayName("token 存在 → 正常取值")
    void tokenPresent() {
        BotTokenConfig c = BotTokenConfig.from(env(Map.of("TELEGRAM_BOT_TOKEN", "123456:abc")));

        assertThat(c.token()).isEqualTo("123456:abc");
    }

    @Test
    @DisplayName("token 缺失 → fail-fast（拒绝启动，而非静默不响应）")
    void tokenMissingFailsFast() {
        assertThatThrownBy(() -> BotTokenConfig.from(env(Map.of())))
                .isInstanceOf(TggException.class)
                .hasMessageContaining("TELEGRAM_BOT_TOKEN");
    }

    @Test
    @DisplayName("token 空白 → 同样 fail-fast")
    void tokenBlankFailsFast() {
        assertThatThrownBy(() -> BotTokenConfig.from(env(Map.of("TELEGRAM_BOT_TOKEN", "   "))))
                .isInstanceOf(TggException.class);
    }

    @Test
    @DisplayName("来源函数为 null → 构造期拒绝")
    void nullSourceRejected() {
        assertThatThrownBy(() -> BotTokenConfig.from(null)).isInstanceOf(TggException.class);
    }
}
