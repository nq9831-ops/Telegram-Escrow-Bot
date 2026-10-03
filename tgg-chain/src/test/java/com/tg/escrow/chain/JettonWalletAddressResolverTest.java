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
 * jetton 钱包地址推导的行为固定测试。
 *
 * <p>这一层存在的理由：托管合约的 {@code ownJettonWallet} 是**入金守门的信任锚点**
 * （见 contracts/types.tolk 中 EscrowExtra 的注释）。合约侧只认「我方钱包」发来的
 * transfer_notification；一旦这个锚点被写错（写成零地址、写空、或写成一个语法合法的
 * 垃圾值），合约不会报错崩溃，而是**静默地永远收不到合法入金**——这正是最坏的一类失败。
 *
 * <p>因此在「链上查询 → 写入部署参数」这条路径上，本层钉死三条纪律：
 * <ol>
 *   <li><b>查询不可用 → 抛错</b>，绝不降级返回空值或缓存值（与 ChainSource 的契约一致）；</li>
 *   <li><b>返回值必须通过校验</b>：非空、形态合法（36 字节 base64url）、且**不是全零地址**；</li>
 *   <li>校验失败一律抛错——宁可让部署失败，也不要写进一个永远不会匹配的锚点。</li>
 * </ol>
 */
class JettonWalletAddressResolverTest {

    /** 合法形态且**校验和正确**的样例地址（由 ton4j 构造，见 TestAddresses）。 */
    private static final String VALID_WALLET = TestAddresses.valid();

    /** 全零地址的 friendly 形式——语法合法但语义非法：任何持有者都不匹配。 */
    private static final String ZERO_ADDRESS = "EQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAM9c";

    private static final String MASTER = "EQCxE6mUtQJKFnGfaROTKOt1lZbDiiX1kCixRv7Nw2Id_sDs";
    private static final String OWNER = "EQAbcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJK";

    /** 手写替身：只把「节点返回了什么」暴露出来，不引入 mock 框架。 */
    private static JettonWalletQuery queryReturning(String reply) {
        return (master, owner) -> reply;
    }

    private static JettonWalletQuery queryFailing(ChainUnavailableException failure) {
        return (master, owner) -> {
            throw failure;
        };
    }

    @Test
    @DisplayName("查询给出合法地址 → 原样返回")
    void resolvesAddressFromQuery() {
        var resolver = new JettonWalletAddressResolver(queryReturning(VALID_WALLET));

        assertThat(resolver.resolve(MASTER, OWNER)).isEqualTo(VALID_WALLET);
    }

    @Test
    @DisplayName("查询不可用 → 抛 ChainUnavailableException，不返回任何值")
    void failsClosedWhenQueryUnavailable() {
        var failure = new ChainUnavailableException("node down");
        var resolver = new JettonWalletAddressResolver(queryFailing(failure));

        assertThatThrownBy(() -> resolver.resolve(MASTER, OWNER))
                .isInstanceOf(ChainUnavailableException.class);
    }

    @Test
    @DisplayName("查询返回空或空白 → 抛错（不把空锚点写进部署参数）")
    void rejectsBlankReply() {
        assertThatThrownBy(() -> new JettonWalletAddressResolver(queryReturning("   "))
                .resolve(MASTER, OWNER))
                .isInstanceOf(ChainUnavailableException.class);

        assertThatThrownBy(() -> new JettonWalletAddressResolver(queryReturning(null))
                .resolve(MASTER, OWNER))
                .isInstanceOf(ChainUnavailableException.class);
    }

    @Test
    @DisplayName("查询返回全零地址 → 抛错（语法合法但永远不会匹配到资金）")
    void rejectsZeroAddressReply() {
        assertThatThrownBy(() -> new JettonWalletAddressResolver(queryReturning(ZERO_ADDRESS))
                .resolve(MASTER, OWNER))
                .isInstanceOf(ChainUnavailableException.class);
    }

    @Test
    @DisplayName("查询返回形态非法的串 → 抛错")
    void rejectsMalformedReply() {
        assertThatThrownBy(() -> new JettonWalletAddressResolver(queryReturning("not-an-address"))
                .resolve(MASTER, OWNER))
                .isInstanceOf(ChainUnavailableException.class);
    }
}
