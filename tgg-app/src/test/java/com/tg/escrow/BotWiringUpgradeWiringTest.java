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
package com.tg.escrow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tg.escrow.chain.ChainGateway;
import com.tg.escrow.chain.ChainUnavailableException;
import java.time.Clock;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * 升级写链栈的装配接线测试（遗留修复 T2）——<b>真实依赖直调</b>（非 mock）：
 * 直接走 {@link ChainWiring} 的装配方法，验「配置 → 接线」的边界是否真的接上。
 *
 * <h2>零触网的接线观测点</h2>
 * <p>接线与否的分界用<b>输入校验顺序</b>区分，不真发消息：
 * <ul>
 *   <li>未接线：{@code requireSender()} 先抛「写链栈未接线」（fail-closed，行为不变）；</li>
 *   <li>已接线：进入 {@code sendToContract} 的入参校验，空地址抛「目标合约地址未提供」
 *       ——在 seqno 读取/网络触达<b>之前</b>，故本测试永不触网。</li>
 * </ul>
 */
class BotWiringUpgradeWiringTest {

    /** 合法 seed（32 字节全零的 Base64）——只为装配形态，不涉真密钥。 */
    private static final String VALID_SEED_B64 = Base64.getEncoder().encodeToString(new byte[32]);
    private static final long WALLET_ID = 0L;   // 任意值——本测试只验装配形态；生产默认 2147483645（见 application.yml 注释）

    private final ChainWiring wiring = new ChainWiring();

    @Test
    void seedBlankKeepsFailClosed() {
        ChainGateway gw = wiring.chainGateway("", true, " ", "", WALLET_ID, Clock.systemUTC());
        assertThatThrownBy(() -> gw.applyUpgrade(" "))
                .isInstanceOf(ChainUnavailableException.class)
                .hasMessageContaining("写链栈未接线");
    }

    @Test
    void seedConfiguredWiresUpgradeStack() {
        ChainGateway gw = wiring.chainGateway("", true, VALID_SEED_B64, "", WALLET_ID, Clock.systemUTC());
        // 边界已接上：不再是「写链栈未接线」，而是下游的入参校验（见类注释「零触网的接线观测点」）
        assertThatThrownBy(() -> gw.applyUpgrade(" "))
                .isInstanceOf(ChainUnavailableException.class)
                .hasMessageContaining("目标合约地址未提供")
                .satisfies(ex -> assertThat(ex.getMessage()).doesNotContain("未接线"));
    }

    @Test
    void seedWrongLengthFailsFastWithoutEchoingSeed() {
        String badSeed = Base64.getEncoder().encodeToString(new byte[16]);
        assertThatThrownBy(() -> wiring.chainGateway("", true, badSeed, "", WALLET_ID, Clock.systemUTC()))
                .isInstanceOf(IllegalArgumentException.class)
                // fail-fast（配错是真错误）且**不回显 seed**——LogSanitizer 不遮 seed，文案是唯一防线
                .satisfies(ex -> assertThat(ex.getMessage()).doesNotContain(badSeed));
    }

    @Test
    void seedNotBase64FailsFastWithoutEchoingSeed() {
        String badSeed = "!!!not-base64!!!";
        assertThatThrownBy(() -> wiring.chainGateway("", true, badSeed, "", WALLET_ID, Clock.systemUTC()))
                .isInstanceOf(IllegalArgumentException.class)
                .satisfies(ex -> assertThat(ex.getMessage()).doesNotContain(badSeed));
    }

    // ---- 助记词来源（2026-10-02：acton 原生导出的是助记词，配置面直接支持）----

    @Test
    void mnemonicConfiguredWiresUpgradeStack() {
        // 助记词 → 程序内派生 seed → 与 seed 直配同一条装配路径（观测点同上：进下游入参校验）
        ChainGateway gw = wiring.chainGateway("", true, "",
                "virus safe such tourist balance august track home now outside fantasy series "
                        + "adapt shift next coach mad industry layer great misery season squeeze tunnel",
                WALLET_ID, Clock.systemUTC());
        assertThatThrownBy(() -> gw.applyUpgrade(" "))
                .isInstanceOf(ChainUnavailableException.class)
                .hasMessageContaining("目标合约地址未提供")
                .satisfies(ex -> assertThat(ex.getMessage()).doesNotContain("未接线"));
    }

    @Test
    void bothSeedAndMnemonicFailFastAsAmbiguous() {
        assertThatThrownBy(() -> wiring.chainGateway("", true, VALID_SEED_B64,
                "virus safe such tourist balance august track home now outside fantasy series "
                        + "adapt shift next coach mad industry layer great misery season squeeze tunnel",
                WALLET_ID, Clock.systemUTC()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("只能配一个");
    }

    @Test
    void invalidMnemonicFailsFast() {
        assertThatThrownBy(() -> wiring.chainGateway("", true, "",
                "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo",
                WALLET_ID, Clock.systemUTC()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
