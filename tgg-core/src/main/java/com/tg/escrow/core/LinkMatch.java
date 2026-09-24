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

/**
 * 链接检出的结果（原文档 G9）。
 *
 * @param url       命中的原始片段
 * @param host      归一化（小写、去 scheme/路径/端口）的主机名
 * @param allowed   是否在白名单内
 * @param shortener 是否命中短链域名表
 */
public record LinkMatch(String url, String host, boolean allowed, boolean shortener) {

    /** 是否需要处置：非白名单，或是短链（短链会隐藏真实目标）。 */
    public boolean suspicious() {
        return shortener || !allowed;
    }
}
