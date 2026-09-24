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

import java.util.function.Function;

/**
 * Bot Token 配置（S1 fail-fast 约束）。
 *
 * <p>缺失/空白 token <b>拒绝启动</b>：带着空 token 起来的 Bot 不报错、只是静默不响应——
 * 用户看到"机器人没反应"，极难排查。fail-fast 让配置错误在启动秒级暴露。
 *
 * <p>token 只从环境变量 {@value #ENV_KEY} 读取（不入库、不进日志——见 {@code LogSanitizer}）。
 */
public final class BotTokenConfig {

    /** Token 环境变量名。 */
    public static final String ENV_KEY = "TELEGRAM_BOT_TOKEN";

    private final String token;

    private BotTokenConfig(String token) {
        this.token = token;
    }

    /**
     * 从配置来源读取 token（生产用 {@code System::getenv}；测试可注入）。
     *
     * @param source 键值来源（不得为 {@code null}）
     * @throws TggException token 缺失或空白——fail-fast
     */
    public static BotTokenConfig from(Function<String, String> source) {
        if (source == null) {
            throw new TggException("Bot Token：配置来源未提供");
        }
        String token = source.apply(ENV_KEY);
        if (token == null || token.isBlank()) {
            throw new TggException("Bot Token：环境变量 " + ENV_KEY + " 缺失或空白——拒绝启动"
                    + "（空 token 启动会静默不响应，比启动失败更难排查）");
        }
        return new BotTokenConfig(token.trim());
    }

    /** token 值（<b>不得写入日志</b>）。 */
    public String token() {
        return token;
    }
}
