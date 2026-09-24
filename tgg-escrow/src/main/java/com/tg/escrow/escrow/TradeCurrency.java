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
package com.tg.escrow.escrow;

import com.tg.escrow.common.EscrowException;

import java.util.Locale;

/**
 * 交易币种白名单——本平台<b>只结算 TON 与 TON 链上的 USDT</b>，其余一律不受理。
 *
 * <h2>为什么是白名单而不是「非空即可」</h2>
 * <p>币种在资金流程里不是显示字段而是<b>结算口径</b>。此前服务端只校验「非空白」，
 * 于是调用方可创建任意币种的订单（如 {@code BTC}），而下游只能结算 TON/USDT——
 * 这类订单在落库那一刻就是无法履约的坏数据。是否受理某币种是<b>事实错误</b>
 * （永远不该接受），故在装配请求时就 fail-closed 拦下，而不是留到结算期才发现。
 *
 * <h2>归一化</h2>
 * <p>比较前统一 {@code trim + toUpperCase}，避免 {@code "usdt"} 与 {@code "USDT"}
 * 在库里并存造成同一币种被当成两种。{@link #requireSupported} 返回规范枚举，
 * 调用方应以此为准落入库/回执。
 *
 * <p>白名单刻意保持极小：新增币种是产品决策，应显式改这里，而不是被外部输入悄悄引入。
 */
public enum TradeCurrency {

    /** TON 原生币。 */
    TON,
    /** TON 链上的 USDT（jetton）；ticker 记 USDT，链归属由平台隐含。 */
    USDT;

    /**
     * 校验并归一化外部传入的币种串。
     *
     * @param raw 原始币种串（可带大小写/空白；来自命令层或 HTTP 体）
     * @return 命中的规范枚举
     * @throws EscrowException 未提供、空白，或不在白名单内——<b>fail-closed</b>，绝不静默放行
     */
    public static TradeCurrency requireSupported(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new EscrowException("交易币种：未提供");
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        for (TradeCurrency candidate : values()) {
            if (candidate.name().equals(normalized)) {
                return candidate;
            }
        }
        throw new EscrowException("交易币种：不支持「" + raw + "」——当前仅支持 TON 与 USDT（TON 链）");
    }
}
