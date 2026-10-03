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

/**
 * 链上只读数据源。实现契约：①不可用（超时/限流/断连）抛 {@link ChainUnavailableException}，
 * 调用方据以排除该源；②其他异常<b>不得吞</b>（实现 bug 原样上抛，伪装成"不可用"会让 bug 长期潜伏）；
 * ③<b>不得编造数据</b>——读不到就抛，不返回 0 或缓存值（否则交叉验证失去意义）。
 */
public interface ChainSource {

    /** 来源标识（如 {@code liteserver-self}），用于证据记录与告警定位。 */
    String name();

    /** 读取地址当前余额及其确认深度；源不可用抛 {@link ChainUnavailableException}。 */
    BalanceObservation observeBalance(String address) throws ChainUnavailableException;
}
