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
package com.tg.escrow.common;

/**
 * 担保交易领域的业务异常基类。
 *
 * <p>区别于通用的 {@link TggException}：本异常专指担保交易领域的失败
 * （状态迁移非法、订单数据异常等），使上层能把「担保领域问题」单独挑出来处理。
 *
 * <p>继承 {@link TggException} 而非受检异常：状态迁移违规是<b>不该被 catch 后继续</b>的
 * 情形——静默吞掉它等于让一笔资金停在未知状态。
 */
public class EscrowException extends TggException {

    private static final long serialVersionUID = 1L;

    public EscrowException(String message) {
        super(message);
    }

    public EscrowException(String message, Throwable cause) {
        super(message, cause);
    }
}
