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

import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 本部署**允许受理**的币种集合——「允许什么」的那一层。
 *
 * <h2>与 {@link TradeCurrency} 的分工（本类为什么存在）</h2>
 * <p>{@link TradeCurrency} 回答「<b>能不能</b>」：本平台只结算 TON 与 TON 链上的 USDT，
 * 这是<b>能力事实</b>，不该随部署而变。本类回答「<b>允许什么</b>」：同一个平台可以把受理范围
 * 收窄到只有 USDT——那是<b>部署选择</b>，将来也可能放开。
 *
 * <p>把两者混在一起（例如删掉 {@code TradeCurrency.TON} 常量）等于把部署选择写成能力事实：
 * 想恢复只能改代码重发版，而且后来者无从知道当初为什么删。
 *
 * <h2>空配置的方向（与项目"空=能力关闭"的取向刻意不同）</h2>
 * <p><b>未配置（空白）= 不收窄</b>，即 {@link #all()}。这是刻意的：收窄开关默认关着，
 * 既不改既有行为，也不给出"看起来已经限制住了"的错觉。
 * 但注意——这不削弱 fail-closed：未知币种仍由 {@link TradeCurrency#requireSupported} 拦下，
 * 本类只在其之上<b>再收窄</b>，从不放宽。
 *
 * <p><b>写错</b>（如 {@code USDT,BTC}）在构造期直接抛：{@code BTC} 不在 {@link TradeCurrency}
 * 白名单内，属配置错误，必须启动即失败，而不是静默丢弃该项。
 * <b>解析后为空</b>（如 {@code ","}）同样抛——那几乎一定是笔误，
 * 若静默当作"全允许"就把笔误掩盖了。
 */
public final class AllowedCurrencies {

    private final Set<TradeCurrency> allowed;

    private AllowedCurrencies(Set<TradeCurrency> allowed) {
        this.allowed = allowed;
    }

    /** 不收窄：允许 {@link TradeCurrency} 全集（未配置时的默认，行为与从前一致）。 */
    public static AllowedCurrencies all() {
        return new AllowedCurrencies(EnumSet.allOf(TradeCurrency.class));
    }

    /**
     * 从配置串解析（逗号分隔；大小写与空白归一）。
     *
     * @param csv 配置值；{@code null} 或空白 = 不收窄（返回 {@link #all()}）
     * @return 解析出的允许集
     * @throws EscrowException 含未支持币种（配置错误），或解析后为空（多为笔误）
     */
    public static AllowedCurrencies fromConfig(String csv) {
        if (csv == null || csv.isBlank()) {
            return all();
        }
        Set<TradeCurrency> parsed = EnumSet.noneOf(TradeCurrency.class);
        for (String token : csv.split(",")) {
            if (token.isBlank()) {
                continue;
            }
            // 逐项过 TradeCurrency 的能力白名单：不认识就抛，绝不静默丢弃
            parsed.add(TradeCurrency.requireSupported(token));
        }
        if (parsed.isEmpty()) {
            throw new EscrowException("币种允许集：配置「" + csv + "」解析后为空——"
                    + "「不收窄」的写法是**留空该配置项**，而不是写成空列表");
        }
        return new AllowedCurrencies(parsed);
    }

    /** 该币种是否在本部署的受理范围内。 */
    public boolean allows(TradeCurrency currency) {
        return currency != null && allowed.contains(currency);
    }

    /**
     * 校验币种在本部署的受理范围内——不在即抛，<b>fail-closed</b>。
     *
     * @param currency 已完成能力校验的规范枚举（调用方先用 {@link TradeCurrency#requireSupported}）
     * @throws EscrowException 未提供，或不在允许集内
     */
    public void requireAllowed(TradeCurrency currency) {
        if (currency == null) {
            throw new EscrowException("交易币种：未提供");
        }
        if (!allowed.contains(currency)) {
            throw new EscrowException("交易币种：" + currency.name()
                    + " 不在本部署允许受理的范围内（当前允许：" + describe() + "）");
        }
    }

    /** 供日志与错误文案：稳定顺序（字母序）的允许集描述。 */
    public String describe() {
        return allowed.stream().map(Enum::name).sorted().collect(Collectors.joining(", "));
    }
}
