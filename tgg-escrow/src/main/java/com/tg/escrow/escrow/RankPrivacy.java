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
package com.tg.escrow.escrow;

/**
 * 排名隐私（ET-80）：<b>默认脱敏</b>展示用户名，用户主动公开才显示全名。
 *
 * <p>脱敏保留首尾（{@code userA} → {@code u***A}）：既不可反查，又让本人能认出自己。
 * 13 号材料定案（2.2）：榜单脱敏不等于交易对手不可见——交易前的风险评估是正当需要，
 * 由调用方在"交易参考"场景另行展示，本类只管榜单展示。
 */
public final class RankPrivacy {

    private RankPrivacy() {
    }

    /**
     * 榜单展示名。
     *
     * @param username      用户名
     * @param publicProfile 用户是否选择公开
     * @return 公开时原名；否则脱敏（首字符 + {@code ***} + 末字符）
     */
    public static String display(String username, boolean publicProfile) {
        if (username == null || username.isBlank()) {
            return "u***";
        }
        if (publicProfile) {
            return username;
        }
        String name = username.trim();
        if (name.length() <= 2) {
            return name.charAt(0) + "***";
        }
        return name.charAt(0) + "***" + name.charAt(name.length() - 1);
    }
}
