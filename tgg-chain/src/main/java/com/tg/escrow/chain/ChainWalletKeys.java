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
import org.ton.ton4j.address.Address;
import org.ton.ton4j.mnemonic.Mnemonic;
import org.ton.ton4j.smartcontract.wallet.v5.WalletV5;

import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * 联邦写链钱包的私钥派生（助记词 → 32 字节 seed）。
 *
 * <h2>为什么需要它</h2>
 * <p>{@code AdnlChainSender} 需要 32 字节 ed25519 seed（{@code keyPair_fromSeed}）；
 * 而 acton 原生的钱包导出是<b>助记词</b>（{@code acton wallet export-mnemonic}）——
 * 中间这一步派生由本类承担，让部署者可以直接把助记词填进配置，不必先手工转换。
 *
 * <h2>派生关系（TON 标准）</h2>
 * <p>{@code PBKDF2-HMAC-SHA512(助记词, "TON seed version", 100k, 64)} 得 64 字节 TON seed；
 * ed25519 用其<b>前 32 字节</b>作为 seed（与 TON 生态 {@code nacl.sign.keyPair.fromSeed(seed[0:32])}
 * 一致）。本类以「派生结果与 ton4j 自身 {@code Mnemonic.toKeyPair} 的公钥一致」单测钉住
 * （见 {@code ChainWalletKeysTest}）。
 *
 * <h2>密码保护的钱包</h2>
 * <p>password-protected 助记词（{@code isPasswordNeeded}）需要附加口令才能派生——
 * 本配置面不携带口令，遇此类助记词<b>明确拒绝</b>（fail-fast，附替代指引），而不是派生出一个
 * 与真实钱包无关的密钥（那会表现为"签名被拒"，误导排查方向）。
 */
public final class ChainWalletKeys {

    /** ed25519 seed 长度（字节）——{@code AdnlChainSender} 的入参口径。 */
    public static final int SEED_LENGTH = 32;

    private ChainWalletKeys() {
    }

    /**
     * 助记词 → 32 字节 ed25519 seed。
     *
     * @param words 助记词（空白分隔的单词列表，24 词常见；须通过词表与校验和验证）
     * @throws IllegalArgumentException 空列表 / 非法助记词 / 密码保护
     */
    public static byte[] seedFromMnemonic(List<String> words) {
        if (words == null || words.isEmpty()) {
            throw new IllegalArgumentException("助记词：词列表不可为空");
        }
        try {
            if (!Mnemonic.isValid(words, "")) {
                throw new IllegalArgumentException(
                        "助记词：词表或校验和不符（必须是 TON 标准 BIP-39 词表的合法序列）");
            }
            if (Mnemonic.isPasswordNeeded(words)) {
                throw new IllegalArgumentException(
                        "助记词：该钱包受密码保护（password-protected）——本配置不支持附加口令，"
                                + "请改用无密码钱包，或先在其钱包客户端导出 32 字节 seed 填 "
                                + "tgg.chain.upgrade.wallet-seed-base64");
            }
            byte[] full = Mnemonic.toSeed(words);
            if (full == null || full.length < SEED_LENGTH) {
                throw new IllegalArgumentException(
                        "助记词：派生结果异常（长度 " + (full == null ? "null" : full.length) + "）");
            }
            byte[] seed = new byte[SEED_LENGTH];
            System.arraycopy(full, 0, seed, 0, SEED_LENGTH);
            return seed;
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            // 运行环境缺 PBKDF2/HMAC——拒绝以降级方式输出密钥
            throw new IllegalStateException("助记词派生失败（运行环境缺少所需算法）：" + e.getMessage(), e);
        }
    }

    /**
     * 助记词字符串 → 32 字节 seed（按空白切分，供配置面直接调用）。
     *
     * @param mnemonic 以空白分隔的助记词（可含首尾空白）
     */
    public static byte[] seedFromMnemonic(String mnemonic) {
        if (mnemonic == null || mnemonic.isBlank()) {
            throw new IllegalArgumentException("助记词：不可为空");
        }
        return seedFromMnemonic(List.of(mnemonic.trim().split("\\s+")));
    }

    // ---- v5r1 钱包身份（wallet_id 与地址）——2026-10-02 与官方 TS SDK（@ton/ton）
    // 及 acton 1.2.0 三方对拍定案（固定向量见 ChainWalletKeysTest）----

    /** TON 网络 global id：mainnet 为 -239，testnet 为 -3（官方 SDK 同名常量）。 */
    public static final int MAINNET_GLOBAL_ID = -239;
    public static final int TESTNET_GLOBAL_ID = -3;

    /**
     * v5r1 官方 wallet_id 公式（与 {@code @ton/ton} 的 storeWalletIdV5R1 逐位一致）：
     * <pre>
     * wallet_id = networkGlobalId XOR context
     * context   = [1][workchain:8][versionByte:8][subwalletNumber:15]   // v5r1 的 versionByte=0
     * </pre>
     * 结果恒 &lt; 2^31（bit31 被 XOR 抵消）——因此不受 ton4j「storeUint 拒绝 &ge;2^31」的限制。
     *
     * <p><b>为什么必须用公式而不是拍一个数</b>：wallet_id 录入合约 storage 并参与每个签名的
     * 有效性判断——配错的唯一症状是"钱包拒收"（与签名口径问题同表象），是最难排查的一类错。
     * testnet + subwallet 0 的标准默认值 = {@code 2147483645}；mainnet 同参 = {@code 2147483409}。
     */
    public static long v5r1WalletId(int networkGlobalId, int workchain, int subwalletNumber) {
        long context = (1L << 31)
                | ((workchain & 0xFFL) << 23)
                | (0L << 15)                       // v5r1 的 versionByte = 0
                | (subwalletNumber & 0x7FFFL);
        return (((long) networkGlobalId & 0xFFFFFFFFL) ^ context) & 0xFFFFFFFFL;
    }

    /**
     * 由 seed 推导 v5r1 钱包地址（核销前自检用：把本方法输出与该钱包在 acton/客户端里
     * 显示的地址对比——一致才说明"助记词、wallet_id、isSigAuthAllowed"三者都配对了）。
     *
     * <p>{@code isSigAuthAllowed(true)} 不可省：ton4j 默认 false 会得到<b>另一个地址</b>
     * （v5r1 官方 data 首 bit 规定为 true）——见 {@code AdnlChainSender} 的同款修正。
     */
    public static Address v5r1WalletAddress(byte[] seed, long walletId) {
        if (seed == null || seed.length != SEED_LENGTH) {
            throw new IllegalArgumentException(
                    "钱包地址推导：seed 必须为 " + SEED_LENGTH + " 字节，实为 "
                            + (seed == null ? "null" : seed.length));
        }
        var keyPair = TweetNaclFast.Signature.keyPair_fromSeed(seed);
        return WalletV5.builder()
                .keyPair(keyPair)
                .walletId(walletId)
                .isSigAuthAllowed(true)
                .build()
                .getAddress();
    }
}
