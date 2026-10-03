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

/**
 * 交易群动作失败（{@link TradeGroupPort} 的运行时异常）——公告/置顶/退群的 Telegram 调用失败。
 *
 * <p>语义：<b>群侧失败绝不回滚交易</b>（群是交易的辅助载体），调用方捕获后回执
 * 如实说明「群动作未生效：<原因>」，绝不假装已发公告/已退群。
 */
public class TradeGroupException extends TggException {

    public TradeGroupException(String message) {
        super(message);
    }

    public TradeGroupException(String message, Throwable cause) {
        super(message, cause);
    }
}
