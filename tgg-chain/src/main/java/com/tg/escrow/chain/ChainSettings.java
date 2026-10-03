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

import java.util.Optional;

/**
 * 链上配置（`tgg.chain.*`）。
 *
 * <p><b>jetton master 地址必填、无默认值</b>：仓库里不存在一个长期成立的默认值。
 * 硬编码一个"当前正确"的地址比要求显式配置更危险——前者会在某天静默指向错的合约，
 * 后者在启动时立刻报错。与仓库既有的 fail-closed 取向一致（如 BotToken 缺失即拒绝启动）。
 *
 * <p>校验发生在**构造时**：部署失败前往往已经花掉真金与时间，而配置错误本可以在
 * 启动时就暴露。
 *
 * <p><b>接线状态（如实标注）</b>：本类**已被生产装配消费**——{@code BotWiring.chainGateway}
 * 经 {@link ChainGateway#fromOptional} 构造时校验，"配错即启动失败"已由 Spring 启动链兑现
 * （2026-09-30 核对；此前自述"尚未被消费"已过时）。
 *
 * <p>判据复用 {@link TonAddresses}——与推导结果的校验同一条规则，不另写一份。
 *
 * @param jettonMasterAddress jetton master 合约地址（friendly 形式）
 * @param testnet              是否连测试网（决定 ton4j 的 provider 配置）
 */
public record ChainSettings(String jettonMasterAddress, boolean testnet) {

    public ChainSettings {
        if (!TonAddresses.isUsableAsAnchor(jettonMasterAddress)) {
            throw new IllegalArgumentException(
                    "tgg.chain.jetton-master 必须是可用的 TON 地址（非空、形态合法、非全零），"
                            + "实际值：" + jettonMasterAddress);
        }
    }

    /**
     * 从可选配置派生：<b>未配置（{@code null} / 空白）返回空</b>。
     *
     * <p>「没配」与「配错」是两回事：前者表示<b>链上能力未启用</b>（与项目「空 / 默认关闭 = 未启用」
     * 的取向一致，部署者不配就不会被链上依赖拖住启动）；后者是真错误，必须照常在构造期抛出、
     * 让启动失败——本方法不吞掉非法值。
     *
     * @param jettonMasterAddress 配置值（可空/空白 = 未配置）
     * @param testnet             是否连测试网
     * @return 配置了且合法时返回配置；未配置时返回 {@link Optional#empty()}
     * @throws IllegalArgumentException 配了但不可用（形态非法 / 全零地址）
     */
    public static Optional<ChainSettings> fromOptional(String jettonMasterAddress, boolean testnet) {
        if (jettonMasterAddress == null || jettonMasterAddress.isBlank()) {
            return Optional.empty();
        }
        // 必须 trim：地址判据（TonAddresses）要求「恰 48 字符」，带首尾空白的合法地址会被判为
        // 形态非法而在启动期抛——那是把「配置里多了个空格」误报成「配置错了」。
        return Optional.of(new ChainSettings(jettonMasterAddress.trim(), testnet));
    }
}
