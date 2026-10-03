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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 链上配置的必填校验测试。
 *
 * <p>设计取舍：**jetton master 地址必填、不给默认值**。理由是仓库里没有任何一个
 * "当前的 USDT master 地址"是长期成立的——硬编码一个会过期的常量，比要求显式配置更危险：
 * 前者会在某天静默指向一个错的合约，后者在启动时就报错。
 *
 * <p>校验必须**在构造时**发生（fail-fast），而不是等到部署那一刻——部署失败前已经花掉
 * 真金与时间，而配置错误本可以在启动时就暴露。
 */
class ChainSettingsTest {

    private static final String VALID_MASTER = TestAddresses.valid();
    private static final String ZERO = "EQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAM9c";

    @Test
    @DisplayName("合法 master → 构造成功，并可按需取出")
    void acceptsValidMaster() {
        var settings = new ChainSettings(VALID_MASTER, true);

        assertThat(settings.jettonMasterAddress()).isEqualTo(VALID_MASTER);
        assertThat(settings.testnet()).isTrue();
    }

    @Test
    @DisplayName("master 缺失/空白 → 构造即失败（不给默认值）")
    void rejectsMissingMaster() {
        assertThatThrownBy(() -> new ChainSettings(null, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChainSettings("  ", true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("master 形态非法或全零 → 构造即失败")
    void rejectsUnusableMaster() {
        assertThatThrownBy(() -> new ChainSettings("not-an-address", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("master");
        assertThatThrownBy(() -> new ChainSettings(ZERO, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("fromOptional：未配置（null/空白）→ 空（链上能力未启用），而不是抛")
    void fromOptionalTreatsBlankAsDisabled() {
        assertThat(ChainSettings.fromOptional(null, true)).isEmpty();
        assertThat(ChainSettings.fromOptional("", true)).isEmpty();
        assertThat(ChainSettings.fromOptional("   ", true)).isEmpty();
    }

    @Test
    @DisplayName("fromOptional：带首尾空白的合法地址 → 接受并归一（不把空格误判成配置错）")
    void fromOptionalTrimsSurroundingWhitespace() {
        assertThat(ChainSettings.fromOptional("  " + VALID_MASTER + "\n", true))
                .hasValueSatisfying(s -> assertThat(s.jettonMasterAddress()).isEqualTo(VALID_MASTER));
    }

    @Test
    @DisplayName("fromOptional：配了且合法 → 有值；配了但非法 → 照常抛（不吞配置错误）")
    void fromOptionalKeepsConfiguredValuesStrict() {
        assertThat(ChainSettings.fromOptional(VALID_MASTER, false))
                .hasValueSatisfying(s -> {
                    assertThat(s.jettonMasterAddress()).isEqualTo(VALID_MASTER);
                    assertThat(s.testnet()).isFalse();
                });

        assertThatThrownBy(() -> ChainSettings.fromOptional("not-an-address", true))
                .as("『没配』与『配错』必须分开：后者要在启动期炸")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ChainSettings.fromOptional(ZERO, true))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
