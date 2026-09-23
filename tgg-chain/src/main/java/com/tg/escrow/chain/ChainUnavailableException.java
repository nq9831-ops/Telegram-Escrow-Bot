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
 * 链上数据源不可用，或可用源不足以形成交叉验证。
 *
 * <p>与 {@link ChainDisagreementException} 分开的原因在于<b>应对方式不同</b>：
 * 本异常意味着"没读到"，可以重试、可以扩容数据源；而分歧意味着"读到了但互相矛盾"，
 * 重试无用，必须告警并由人介入。
 */
public class ChainUnavailableException extends TggException {

    private static final long serialVersionUID = 1L;

    public ChainUnavailableException(String message) {
        super(message);
    }

    public ChainUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
