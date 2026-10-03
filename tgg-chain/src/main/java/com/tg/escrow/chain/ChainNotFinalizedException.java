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
 * 多源结论一致，但确认深度尚未达到要求——链上最终性未到。
 *
 * <p>这是独立于另外两个异常的<b>第三种语义</b>，处置方式也不同：
 * <ul>
 *   <li>{@link ChainUnavailableException}：没读到 → 换源 / 重试；</li>
 *   <li>{@link ChainDisagreementException}：读到了但矛盾 → 告警、人工介入，重试无用；</li>
 *   <li>{@link ChainNotFinalizedException}：读到了且一致，但还不够深 → <b>等待后重试</b>，
 *       既不必换源，也不必告警。</li>
 * </ul>
 *
 * <p>把它与"分歧"混为一谈会导致运维误判：一次正常的"等待确认"会被报成数据源异常，
 * 久而久之值班的人就会开始忽略真告警。
 */
public class ChainNotFinalizedException extends TggException {

    private static final long serialVersionUID = 1L;

    public ChainNotFinalizedException(String message) {
        super(message);
    }

    public ChainNotFinalizedException(String message, Throwable cause) {
        super(message, cause);
    }
}
