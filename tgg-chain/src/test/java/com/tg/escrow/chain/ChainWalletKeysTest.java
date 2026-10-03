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

import com.iwebpp.crypto.TweetNaclFast;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.ton.ton4j.mnemonic.Mnemonic;

import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ChainWalletKeys} 的派生关系与跨实现向量固定测试。
 *
 * <h2>三层锚（2026-10-02 定案）</h2>
 * <ol>
 *   <li><b>ton4j 内部一致</b>：{@code keyPair_fromSeed(seedFromMnemonic(words))} 的公钥 ==
 *       {@code Mnemonic.toKeyPair(words)} 的公钥——「TON 64 字节 seed 的前 32 字节 = ed25519 seed」；</li>
 *   <li><b>跨实现向量（golden）</b>：固定 24 词组 + testnet 默认 wallet_id 推导出的地址，
 *       与 <b>acton 1.2.0（Rust）</b>与<b>官方 TS SDK {@code @ton/ton}（JS）</b>各自独立算出的
 *       地址逐字节相同（三方同址 = 派生链 + wallet_id 公式 + data 布局全部对齐）。
 *       词表为测试专用（无资金），仅作回归锚；</li>
 *   <li><b>wallet_id 公式</b>：{@code networkGlobalId XOR ([1][wc][version][subwallet])}——
 *       testnet/subwallet0 = 2147483645、mainnet 同参 = 2147483409（@ton/ton
 *       {@code storeWalletIdV5R1} 逐位复刻）。</li>
 * </ol>
 *
 * <p><b>本向量的来由（防"自拍 golden"）</b>：先以 acton {@code wallet import} 独立算出
 * {@code kQDuEjuY98iyyn14z1yKNuGtBiEzbB9ARt-12x2Yy5dhKRYw}（= raw {@code 0:ee123b98…}），
 * 再以官方 TS SDK 复算同值；本测试的 Java 路径是第三个独立复算。三家一致才固化。
 */
class ChainWalletKeysTest {

    /** 测试专用词组（无资金；来源：ton4j {@code Mnemonic.generate(24)} 一次生成后固化）。 */
    private static final List<String> GOLDEN_WORDS = List.of(
            "virus", "safe", "such", "tourist", "balance", "august",
            "track", "home", "now", "outside", "fantasy", "series",
            "adapt", "shift", "next", "coach", "mad", "industry",
            "layer", "great", "misery", "season", "squeeze", "tunnel");

    private static final String GOLDEN_SEED_BASE64 = "n8u+2W30sPqIZOAbaZEwuaaarI6/wTbTSDlqgKMtyD8=";

    /** acton + 官方 TS SDK 双确认的地址（testnet 默认：walletId=2147483645）。 */
    private static final String GOLDEN_ADDRESS_RAW =
            "0:ee123b98f7c8b2ca7d78cf5c8a36e1ad0621336c1f4046dfb5db1d98cb976129";

    @Test
    @DisplayName("核心对拍：seedFromMnemonic 与 ton4j toKeyPair 的公钥一致（[0:32] 关系钉住）")
    void derivedSeedMatchesTon4jKeyPair() throws Exception {
        List<String> words = Mnemonic.generate(24);
        assertThat(Mnemonic.isValid(words, "")).isTrue();

        byte[] seed = ChainWalletKeys.seedFromMnemonic(words);
        assertThat(seed).hasSize(32);

        TweetNaclFast.Signature.KeyPair fromDerived =
                TweetNaclFast.Signature.keyPair_fromSeed(seed);
        org.ton.ton4j.mnemonic.Pair ton4jPair = Mnemonic.toKeyPair(words);

        assertThat(fromDerived.getPublicKey())
                .as("两条派生路径（本类 [0:32] 切片 vs ton4j toKeyPair）必须同公钥")
                .isEqualTo(ton4jPair.getPublicKey());
    }

    @Test
    @DisplayName("跨实现向量：golden 词 → seed → testnet 地址 == acton == 官方 TS SDK")
    void goldenVectorMatchesOfficialImplementations() {
        byte[] seed = ChainWalletKeys.seedFromMnemonic(GOLDEN_WORDS);

        assertThat(Base64.getEncoder().encodeToString(seed)).isEqualTo(GOLDEN_SEED_BASE64);

        long walletId = ChainWalletKeys.v5r1WalletId(
                ChainWalletKeys.TESTNET_GLOBAL_ID, 0, 0);
        assertThat(ChainWalletKeys.v5r1WalletAddress(seed, walletId).toRaw())
                .as("三方对拍锚：任何一侧（派生/walletId/data 布局）漂移先红在这里")
                .isEqualTo(GOLDEN_ADDRESS_RAW);
    }

    @Test
    @DisplayName("wallet_id 公式：testnet=2147483645 / mainnet=2147483409（subwallet 0 wc 0 v5r1）")
    void v5r1WalletIdFormula() {
        assertThat(ChainWalletKeys.v5r1WalletId(ChainWalletKeys.TESTNET_GLOBAL_ID, 0, 0))
                .isEqualTo(2147483645L);
        assertThat(ChainWalletKeys.v5r1WalletId(ChainWalletKeys.MAINNET_GLOBAL_ID, 0, 0))
                .isEqualTo(2147483409L);
        // subwallet 参与低位：与官方公式的 15 bit 掩码一致
        assertThat(ChainWalletKeys.v5r1WalletId(ChainWalletKeys.TESTNET_GLOBAL_ID, 0, 1))
                .isEqualTo(2147483644L);
    }

    @Test
    @DisplayName("用户粘贴入口：带换行/缩进的 24 词字符串 → 与钱包客户端一致的地址（验收①）")
    void userPasteEntryPointProducesSameAddress() {
        // 模拟用户从 acton export-mnemonic 复制粘贴的真实形态：多行 + 缩进 + 首尾空白
        String pasted = "  virus safe such tourist balance august track\n"
                + "  home now outside fantasy series adapt shift next coach\n"
                + "  mad industry layer great misery season squeeze tunnel  ";

        byte[] seed = ChainWalletKeys.seedFromMnemonic(pasted);

        assertThat(ChainWalletKeys.v5r1WalletAddress(seed, 2147483645L).toRaw())
                .as("配置面收到的就是这个字符串——推导地址必须与钱包客户端显示一致")
                .isEqualTo(GOLDEN_ADDRESS_RAW);
    }

    @Test
    @DisplayName("确定性：同助记词两次派生（列表/字符串入口）逐字节相同")
    void derivationIsDeterministic() {
        byte[] a = ChainWalletKeys.seedFromMnemonic(GOLDEN_WORDS);
        byte[] b = ChainWalletKeys.seedFromMnemonic(String.join(" ", GOLDEN_WORDS));

        assertThat(a).isEqualTo(b).hasSize(32);
    }

    @Test
    @DisplayName("非法输入：空列表 / 非法词组 / 空字符串 / 非法 seed 长度一律拒绝（fail-closed）")
    void invalidInputsRejected() {
        assertThatThrownBy(() -> ChainWalletKeys.seedFromMnemonic(List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("不可为空");
        assertThatThrownBy(() -> ChainWalletKeys.seedFromMnemonic("   "))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("不可为空");
        assertThatThrownBy(() -> ChainWalletKeys.seedFromMnemonic(
                List.of("zoo", "zoo", "zoo", "zoo", "zoo", "zoo",
                        "zoo", "zoo", "zoo", "zoo", "zoo", "zoo")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("助记词");
        assertThatThrownBy(() -> ChainWalletKeys.v5r1WalletAddress(new byte[31], 0L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("32");
    }
}
