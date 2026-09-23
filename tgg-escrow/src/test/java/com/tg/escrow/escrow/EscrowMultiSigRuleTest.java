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

import com.tg.escrow.escrow.EscrowMultiSigRule.Party;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 多签裁决规则的行为固定测试。
 *
 * <p>核心不变式：<b>裁决必须包含联邦仲裁节点</b>；{@code 买方 + 卖方} 组合<b>无效</b>——
 * 买卖双方串通即可自行放款/退款，多签形同虚设。
 *
 * <p>本测试对全部两方组合做<b>穷举对表</b>，而不是只挑几个用例：将来若增加参与方，
 * 遗漏的组合会让规则被悄悄放宽，而单点用例察觉不到。
 */
class EscrowMultiSigRuleTest {

    @Test
    @DisplayName("阈值常量与「2/3 里的 2」一致")
    void thresholdIsTwo() {
        assertThat(EscrowMultiSigRule.REQUIRED_SIGNERS).isEqualTo(2);
    }

    @Test
    @DisplayName("穷举全部两方组合：仅含联邦的组合通过，买卖组合必须失败")
    void everyTwoPartyCombinationIsCoveredByTheRule() {
        Set<Set<Party>> pairs = Stream.of(Party.values())
                .flatMap(a -> Stream.of(Party.values())
                        .filter(b -> a != b)
                        .map(b -> EnumSet.of(a, b)))
                .map(s -> (Set<Party>) s)
                .collect(Collectors.toSet());

        // 3 方选 2 即 3 种组合——数量本身也要钉住，否则枚举改名会静默减少组合
        assertThat(pairs).hasSize(3);

        for (Set<Party> pair : pairs) {
            boolean expected = pair.contains(Party.FEDERATION);
            assertThat(EscrowMultiSigRule.satisfies(pair))
                    .as("两方组合 %s 的判定", pair)
                    .isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("买卖双方联合签名无效——串通即可自行放款，多签形同虚设")
    void buyerAndSellerTogetherAreRejected() {
        Set<Party> collusion = Set.of(Party.BUYER, Party.SELLER);

        assertThat(EscrowMultiSigRule.satisfies(collusion))
                .as("买卖双方串通必须被拒绝")
                .isFalse();
    }

    @Test
    @DisplayName("联邦 + 买方 通过")
    void federationWithBuyerPasses() {
        assertThat(EscrowMultiSigRule.satisfies(Set.of(Party.FEDERATION, Party.BUYER))).isTrue();
    }

    @Test
    @DisplayName("联邦 + 卖方 通过")
    void federationWithSellerPasses() {
        assertThat(EscrowMultiSigRule.satisfies(Set.of(Party.FEDERATION, Party.SELLER))).isTrue();
    }

    @Test
    @DisplayName("三方齐签通过")
    void allThreePass() {
        assertThat(EscrowMultiSigRule.satisfies(
                Set.of(Party.FEDERATION, Party.BUYER, Party.SELLER))).isTrue();
    }

    @Test
    @DisplayName("不足阈值：任何单方签名都不通过（含联邦单独签）")
    void anySingleSignerIsInsufficient() {
        for (Party p : Party.values()) {
            assertThat(EscrowMultiSigRule.satisfies(Set.of(p)))
                    .as("单方 %s 不应通过", p)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("空集与 null 都不通过（fail-closed，不抛异常）")
    void emptyAndNullAreRejected() {
        assertThat(EscrowMultiSigRule.satisfies(Set.of())).isFalse();
        assertThat(EscrowMultiSigRule.satisfies(null)).isFalse();
    }
}
