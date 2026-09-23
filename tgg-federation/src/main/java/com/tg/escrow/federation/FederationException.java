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
package com.tg.escrow.federation;

import com.tg.escrow.common.TggException;

/**
 * 联邦模块异常：密钥或签名材料的格式问题。
 *
 * <p>与 {@code EscrowException} 的区别：本异常指<b>信任材料的格式或来源</b>问题
 * （seed 长度不对、公钥串非法），而不是交易状态问题。分开的原因是可运维性——
 * 前者通常意味着配置注入错误，后者意味着业务流程被非法推进。
 */
public class FederationException extends TggException {

    private static final long serialVersionUID = 1L;

    public FederationException(String message) {
        super(message);
    }

    public FederationException(String message, Throwable cause) {
        super(message, cause);
    }
}
