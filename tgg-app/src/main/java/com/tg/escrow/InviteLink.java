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

/**
 * 邀请深链构造器（S7 / Wave 2）——把一次性令牌变成对方可点开的 Telegram 直链。
 *
 * <h2>形态与前提</h2>
 * <p>形如 {@code https://t.me/<bot>?startapp=<token>}：对方点开即在 Telegram 内打开本 bot 的
 * 主 Mini App，且 {@code startapp} 值以 <b>{@code start_param} 字段进入 initData</b>
 * （受 Telegram 签名保护）——故服务端能从已验签的 initData 里取出令牌，无需信任客户端。
 *
 * <p>前提（部署项）：BotFather 里 bot 的 Main Mini App 地址须指向本页，否则 {@code ?startapp=}
 * 打不开正确的页面。
 *
 * <p>抽成独立小类而非在控制器/命令层各拼一次：深链格式是与 Telegram 的接口契约，
 * 只该有一处真相；两处各写一遍，改一处忘一处会静默拼出坏链接。
 */
public final class InviteLink {

    private static final String TEMPLATE = "https://t.me/%s?startapp=%s";

    private final String botUsername;

    /**
     * @param botUsername bot 用户名（可含前导 {@code @}；不得空白）
     */
    public InviteLink(String botUsername) {
        if (botUsername == null || botUsername.isBlank()) {
            throw new TggException("邀请链接：bot 用户名不可为空");
        }
        this.botUsername = botUsername.trim().replace("@", "");
    }

    /**
     * 为令牌生成邀请深链。
     *
     * @param token 一次性邀请令牌（不得空白）
     * @return {@code https://t.me/<bot>?startapp=<token>}
     */
    public String forToken(String token) {
        if (token == null || token.isBlank()) {
            throw new TggException("邀请链接：令牌不可为空");
        }
        return String.format(TEMPLATE, botUsername, token);
    }

    /** 本链接使用的 bot 用户名（不含 {@code @}）。 */
    public String botUsername() {
        return botUsername;
    }
}
