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
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 核销前钱包地址自检（按需启用）——把"配置里的助记词推导出的地址"与钱包客户端
 * （acton `wallet list`）显示的地址逐字符对拍用的执行载体。
 *
 * <h2>用法</h2>
 * <pre>{@code
 * TGG_CHAIN_UPGRADE_WALLET_MNEMONIC="word1 word2 …" \
 *   mvn -B -pl tgg-chain -am test -Dtest=WalletAddressSelfCheckTest
 * }</pre>
 * 打印 {@code WALLET-ADDRESS(testnet kQ)=…}——与 `acton wallet list` 显示的地址（kQ 开头）
 * 逐字符一致才继续部署核销；不一致即 wallet-id 或私钥配错（先修，别去怀疑签名口径，
 * 见 ONLINE-VERIFICATION B10 第 0 步与 HANDOFF 坑 25/26）。
 *
 * <p>可选环境变量：{@code TGG_CHAIN_UPGRADE_WALLET_ID}（默认 2147483645 = v5r1 testnet 标准）、
 * {@code TGG_EXPECTED_WALLET_ADDRESS}（设了则自动断言对拍）。
 *
 * <p>未设置 {@code TGG_CHAIN_UPGRADE_WALLET_MNEMONIC} 时本测试自动跳过（CI/默认构建不受影响）。
 */
@EnabledIfEnvironmentVariable(named = "TGG_CHAIN_UPGRADE_WALLET_MNEMONIC", matches = ".+")
class WalletAddressSelfCheckTest {

    @Test
    @DisplayName("自检：助记词 + wallet-id → 地址（kQ 形态）与钱包客户端对拍")
    void derivedAddressMatchesWalletClient() {
        String mnemonic = System.getenv("TGG_CHAIN_UPGRADE_WALLET_MNEMONIC");
        long walletId = Long.parseLong(System.getenv()
                .getOrDefault("TGG_CHAIN_UPGRADE_WALLET_ID", "2147483645"));

        byte[] seed = ChainWalletKeys.seedFromMnemonic(mnemonic);
        var address = ChainWalletKeys.v5r1WalletAddress(seed, walletId);

        String testnetForm = address.toBounceableTestnet();
        // 打印供人工对拍（surefire 透传 stdout）
        System.out.println("WALLET-ADDRESS(testnet kQ)=" + testnetForm);
        System.out.println("WALLET-ADDRESS(raw)=" + address.toRaw());
        System.out.println("WALLET-ID=" + walletId);

        assertThat((int) address.wc).isEqualTo(0);
        assertThat(address.hashPart).hasSize(32);
        String expected = System.getenv("TGG_EXPECTED_WALLET_ADDRESS");
        if (expected != null && !expected.isBlank()) {
            assertThat(testnetForm)
                    .as("与 TGG_EXPECTED_WALLET_ADDRESS 对拍（acton wallet list 显示的地址）")
                    .isEqualTo(expected.trim());
        }
    }
}
