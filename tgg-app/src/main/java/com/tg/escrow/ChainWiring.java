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

import com.iwebpp.crypto.TweetNaclFast;
import com.tg.escrow.chain.AdnlChainSender;
import com.tg.escrow.chain.AdnlEscrowStateQuery;
import com.tg.escrow.chain.AdnlUpgradeStatusQuery;
import com.tg.escrow.chain.ChainGateway;
import com.tg.escrow.chain.ChainUnavailableException;
import com.tg.escrow.chain.ChainWalletKeys;
import com.tg.escrow.escrow.EscrowOrderLookupPort;
import com.tg.escrow.escrow.EscrowOrderStore;
import com.tg.escrow.escrow.EscrowUpgradeService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.Base64;

/**
 * 链上能力装配（S5）——从 {@link BotWiring} 拆出（2026-10-03 拆分时方法体逐字保留）。
 *
 * <p>覆盖：jetton 钱包地址推导 / 写链钱包（联邦私钥） / 升级编排 / 部署编排 / 链上存证腿。
 * 私钥材料只经环境变量；都未配置 = 写链栈未接线（fail-closed），任一来源非法 = 启动失败。
 */
@Configuration
public class ChainWiring {

    /**
     * 链上能力入口（jetton 钱包地址推导）。
     *
     * <p><b>本 bean 永不为 {@code null}</b>：未配置 {@code tgg.chain.jetton-master} 时它表现为
     * {@link ChainGateway#enabled()} 为 false（能力关闭），而不是「该 bean 不存在」。
     * 后者是脆弱接缝——将来若有组件强制构造注入，未配置会抛
     * {@code NoSuchBeanDefinitionException} 让上下文起不来，把「没配」变成「启动失败」。
     *
     * <p>provider 选 {@link com.tg.escrow.chain.AdnlJettonWalletQuery}（纯 Java，免原生库——
     * 线上受限容器加载不了 Tonlib 所需的 libtonlibjson）。它实现的
     * {@link com.tg.escrow.chain.JettonWalletQuery} 是「可被离线替身替换」的接缝。
     *
     * <p>配了但非法（形态不合法 / 全零）则在 {@link com.tg.escrow.chain.ChainSettings} 构造期抛、
     * 启动即失败——那时是真错误，不该被静默关掉。本 bean 实现 {@link AutoCloseable}，
     * Spring 会在上下文关闭时自动调用 {@code close()}。
     */
    @Bean
    public ChainGateway chainGateway(
            @Value("${tgg.chain.jetton-master:}") String jettonMaster,
            @Value("${tgg.chain.testnet:true}") boolean testnet,
            @Value("${tgg.chain.upgrade.wallet-seed-base64:}") String upgradeWalletSeedBase64,
            @Value("${tgg.chain.upgrade.wallet-mnemonic:}") String upgradeWalletMnemonic,
            // 默认 2147483645 = v5r1 官方标准（testnet/globalId -3、workchain 0、subwallet 0）——
            // 公式与 mainnet 对应值见 ChainWalletKeys.v5r1WalletId；误配表现为钱包拒收（极难排查）
            @Value("${tgg.chain.upgrade.wallet-id:2147483645}") long upgradeWalletId,
            Clock clock) {
        ChainGateway gateway = ChainGateway.fromOptional(jettonMaster, testnet);
        // 链上状态读面（ET-19 对账）：**纯只读、不依赖私钥**——无条件装配（无写链栈也能对账）
        ChainGateway reconciliable = gateway
                .withEscrowStateQuery(new AdnlEscrowStateQuery(testnet));
        byte[] seed = resolveWriteWalletSeed(upgradeWalletSeedBase64, upgradeWalletMnemonic);
        if (seed == null) {
            // 两个私钥来源都未配置 = 写链栈未接线（升级/部署/出款/存证恒抛 ChainUnavailableException）
            return reconciliable;
        }
        AdnlChainSender sender;
        try {
            sender = AdnlChainSender.forNetwork(testnet, upgradeWalletId,
                    TweetNaclFast.Signature.keyPair_fromSeed(seed), clock);
        } catch (ChainUnavailableException ex) {
            // 配了私钥却起不了 ADNL 构件 = 配错/环境坏——启动期失败，不静默降级
            throw new IllegalStateException("升级写链栈装配失败：" + ex.getMessage(), ex);
        }
        return reconciliable.withUpgradeWriters(sender, new AdnlUpgradeStatusQuery(testnet))
                // AdnlChainSender 同时实现部署接缝（S5）：同一联邦钱包承担部署与消息发送
                .withDeploySender(sender);
    }

    /**
     * 写链私钥来源解析：{@code wallet-seed-base64}（32 字节 seed）或 {@code wallet-mnemonic}
     * （TON 助记词，程序内派生）——<b>二选一</b>。
     *
     * <p>都未配置 → {@code null}（写链栈未接线，fail-closed）；都配置 → 抛（歧义，宁可启动失败
     * 也不替部署者猜用哪把）；任一来源自身非法 → 抛。seed 是私钥材料：解析失败文案只说
     * 形态/长度，**绝不回显内容**。
     */
    private static byte[] resolveWriteWalletSeed(String seedBase64, String mnemonic) {
        boolean hasSeed = seedBase64 != null && !seedBase64.isBlank();
        boolean hasMnemonic = mnemonic != null && !mnemonic.isBlank();
        if (hasSeed && hasMnemonic) {
            throw new IllegalArgumentException(
                    "tgg.chain.upgrade 的 wallet-seed-base64 与 wallet-mnemonic 同时配置——"
                            + "两者只能配一个（歧义不猜）");
        }
        if (hasMnemonic) {
            return ChainWalletKeys.seedFromMnemonic(mnemonic.trim());
        }
        if (!hasSeed) {
            return null;
        }
        byte[] seed;
        try {
            seed = Base64.getDecoder().decode(seedBase64.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("tgg.chain.upgrade.wallet-seed-base64 不是合法 Base64");
        }
        if (seed.length != 32) {
            throw new IllegalArgumentException(
                    "tgg.chain.upgrade.wallet-seed-base64 应为 32 字节，实为 " + seed.length + " 字节");
        }
        return seed;
    }

    /** 升级链下多签编排（⑥ 硬约束 1 的链下半边：≥2 方且必含联邦，时钟注入可测）。 */
    @Bean
    public EscrowUpgradeService escrowUpgradeService(Clock clock) {
        return new EscrowUpgradeService(clock);
    }

    /** S5 部署编排：算地址 → 先落库 → 发部署 →（jetton 单）补发 SetJettonWallet。 */
    @Bean
    public EscrowChainDeploymentService escrowChainDeploymentService(
            EscrowOrderLookupPort lookup, EscrowOrderStore store,
            ChainGateway gateway, Clock clock) {
        return new EscrowChainDeploymentService(lookup, store, gateway, clock);
    }

    /** 链上存证腿（ET-43）；盐与日志脱敏共用 {@code tgg.log.salt}（未配置时构造期 warn）。 */
    @Bean
    public ChainEvidenceSink chainEvidenceSink(
            EscrowOrderLookupPort lookup,
            ChainGateway gateway,
            @Value("${tgg.log.salt:}") String salt) {
        return new ChainEvidenceSink(lookup, gateway, salt);
    }
}
