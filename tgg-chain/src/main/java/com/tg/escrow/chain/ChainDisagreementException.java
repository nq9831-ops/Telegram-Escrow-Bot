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
package com.tg.escrow.chain;

import com.tg.escrow.common.TggException;

/**
 * 多个链上数据源对同一事实给出了<b>互相矛盾</b>的结论。
 *
 * <p>这是本模块最重要的异常：它意味着至少有一个源不可信，而**在资金场景下无法判断是哪一个**。
 * 因此处置方式不是重试、不是取多数、更不是取对自己有利的那个——而是停下并告警。
 *
 * <p>为什么"取多数"是错的：若两个源来自同一家云厂商或同一段网络，它们可能一起出错；
 * 而"三源里两票通过"会让一次孤立的分歧被静默吞掉。分歧本身就是要人看的信息。
 */
public class ChainDisagreementException extends TggException {

    private static final long serialVersionUID = 1L;

    public ChainDisagreementException(String message) {
        super(message);
    }

    public ChainDisagreementException(String message, Throwable cause) {
        super(message, cause);
    }
}
