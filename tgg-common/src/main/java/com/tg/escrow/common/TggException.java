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
 * 项目级异常基类。
 *
 * <p>放在 {@code tgg-common} 是为了让各模块共享同一棵异常树：上层可以只 catch 一次
 * 就区分出"本系统的业务失败"与"框架/第三方故障"。
 *
 * <p><b>为什么不直接用 JDK 异常</b>：状态机与配置解析都需要 fail-closed，
 * 而 {@link IllegalArgumentException} 既可能是调用方写错、也可能是数据被篡改，
 * 混在一起后无法在日志与告警里区分对待。
 *
 * <p>非抽象：允许直接抛出（例如状态机守卫这类不属于任何更具体子类的场景）。
 */
public class TggException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public TggException(String message) {
        super(message);
    }

    public TggException(String message, Throwable cause) {
        super(message, cause);
    }
}
