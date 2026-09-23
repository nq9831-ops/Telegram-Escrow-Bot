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
 * 链上只读数据源。
 *
 * <p>实现可以是自建的 liteserver（把信任收回自己手里），也可以是任何第三方 RPC。
 * 本接口<b>不区分</b>二者——区分它们的方式是给它多接几个实例做交叉验证，
 * 而不是在类型上分叉。
 *
 * <h2>实现契约</h2>
 * <ul>
 *   <li>数据源不可用（超时、限流、连接失败）→ 抛 {@link ChainUnavailableException}。
 *       调用方会据此把该源排除在外，其余源仍可继续。</li>
 *   <li><b>其他异常不要吞</b>：实现自身的 bug（空指针、解析错误）应原样向上抛。
 *       把它伪装成"不可用"会让 bug 长期潜伏，只表现为"某个源老是失败"。</li>
 *   <li>不得编造数据。读不到就抛不可用，不要返回 0 或上次的缓存值——
 *       那会让交叉验证失去意义（两个源都返回同一份缓存，看起来"一致"）。</li>
 * </ul>
 */
public interface ChainSource {

    /** 来源标识，用于证据记录与告警定位（例如 {@code liteserver-self}、{@code toncenter-primary}）。 */
    String name();

    /**
     * 读取地址当前余额及其确认深度。
     *
     * @param address 链上地址
     * @return 该源的观测
     * @throws ChainUnavailableException 该源当前不可用
     */
    BalanceObservation observeBalance(String address) throws ChainUnavailableException;
}
