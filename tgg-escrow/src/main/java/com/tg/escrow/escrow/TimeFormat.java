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

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 时刻的<b>展示形态</b>——短 UTC 写法（{@code 2026-10-04 05:33 UTC}），
 * 共享单点避免各出口口径不一（与 {@code MoneyFormat} 对金额的职责同构）。
 *
 * <h2>为什么是 UTC 而不是"本地时间"</h2>
 * <p>Bot 消息<b>无法得知收件人时区</b>（Telegram 不提供该信息）——拿服务端时区冒充
 * "本地"只会制造更隐蔽的误读。标注 UTC 是唯一诚实的写法。Web 端（Mini App）不适用本类：
 * 那里有浏览器时区，由前端自行本地化。
 *
 * <h2>为什么不直接用 {@code Instant.toString()}</h2>
 * <p>它的裸形态（{@code 2026-10-04T05:33:00Z}）不是给人读的：{@code T} 与 {@code Z}
 * 对用户是无意义噪声，秒级精度对"邀请何时过期"也没有信息价值。展示粒度到分钟。
 */
public final class TimeFormat {

    /** 展示形态：{@code yyyy-MM-dd HH:mm 'UTC'}——显式带时区标注，杜绝本地/UTC 误读。 */
    private static final DateTimeFormatter SHORT_UTC =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    private TimeFormat() {
    }

    /**
     * 时刻的展示文本（UTC、分钟粒度）。
     *
     * @param instant 时刻；{@code null} 原样返回 {@code null}（调用方自行决定缺省文案）
     */
    public static String shortUtc(Instant instant) {
        return instant == null ? null : SHORT_UTC.format(instant);
    }
}
