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

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;

/**
 * 升级裁决单（⑥「合约升级入口」的链下多签载荷）。
 *
 * <p>与 {@link EscrowVerdict} 同一范式（A0-2 已拍板：链下多签裁决 + 链上联邦单方执行）：
 * 多签签的是<b>本单的规范字节</b>，链上只信 {@code storage.federation} 一条消息。
 * 载荷<b>绑定四个维度</b>——对哪个合约、什么动作、哪份代码、什么时刻——签名不可挪用：
 * 把「提案 A」的签名挪给「执行 B」会因 codeHash/action 不同而验不过。
 *
 * @param contractAddress 目标托管合约地址（friendly 形式）
 * @param action          升级动作
 * @param codeHashHex     新代码 cell hash（hex，小写；CANCEL/APPLY 绑定被撤销/执行提案的 hash）
 * @param issuedAt        签发时刻（epoch second，随请求携带——EscrowVerdict 的 issuedAt 缺陷同款防线）
 */
public record UpgradeApproval(String contractAddress,
                              Action action,
                              String codeHashHex,
                              long issuedAt) {

    /** 升级生命周期的三个动作（与合约三消息一一对应）。 */
    public enum Action {
        PROPOSE,
        APPLY,
        CANCEL
    }

    public UpgradeApproval {
        Objects.requireNonNull(contractAddress, "contractAddress 未提供");
        Objects.requireNonNull(action, "action 未提供");
        Objects.requireNonNull(codeHashHex, "codeHashHex 未提供");
        if (contractAddress.isBlank()) {
            throw new IllegalArgumentException("contractAddress 不可为空白");
        }
        if (codeHashHex.isBlank()) {
            throw new IllegalArgumentException("codeHashHex 不可为空白");
        }
    }

    /**
     * 规范签名载荷（{@code escrow-upgrade/v1}）——签名方与验签方的唯一字节口径。
     *
     * <p>行式文本（与 {@code EscrowVerdict.canonicalBytes} 同构）；{@code codeHashHex}
     * 统一转小写后入文，消除大小写差异导致的验签假失败。
     */
    public byte[] canonicalBytes() {
        String canonical = "escrow-upgrade/v1\n"
                + "contract:" + contractAddress + "\n"
                + "action:" + action.name().toLowerCase(Locale.ROOT) + "\n"
                + "codeHash:" + codeHashHex.toLowerCase(Locale.ROOT) + "\n"
                + "issuedAt:" + issuedAt + "\n";
        return canonical.getBytes(StandardCharsets.UTF_8);
    }
}
