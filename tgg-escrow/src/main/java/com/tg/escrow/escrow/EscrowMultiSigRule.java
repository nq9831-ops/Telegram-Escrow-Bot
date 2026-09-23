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

import java.util.Set;

/**
 * 担保交易裁决的<b>多签规则</b>——规则的单一定义处。
 *
 * <h2>规则</h2>
 * <ul>
 *   <li>裁决签名<b>必须包含联邦仲裁节点</b>——「担保只认联邦」是核心设计原则；</li>
 *   <li>有效组合：{@code 联邦 + 买方} 或 {@code 联邦 + 卖方}（任二，且必含联邦）；</li>
 *   <li><b>无效</b>：{@code 买方 + 卖方}——买卖双方串通即可自行放款/退款，多签形同虚设。</li>
 * </ul>
 *
 * <h2>为什么买卖双方联合签名必须无效</h2>
 * <p>这条规则容易被误读成"限制用户自由"。实际相反：本项目的多签之意义在于
 * <b>引入一个双方之外的裁定方</b>。若 {@code 买方 + 卖方} 即可处置资金，
 * 那么任何一方想绕开裁定，只需说服对手方——裁定方被彻底架空，
 * 「担保」退化为「双方自管」，而双方自管根本不需要担保交易。
 *
 * <h2>为什么独立成类</h2>
 * <p>规则有<b>多个消费端</b>（链下裁决服务、后台裁决接口，以及将来的链上合约）。
 * 若各端各写一份判断，必然出现"一个入口校验、另一个入口能绕过"——本项目历史上
 * 已有同类教训（"不可自审"只写在命令侧时，后台接口就是后门）。
 * 故规则集中在唯一一处，各端都指向它，并用穷举测试双向钉住。
 *
 * <h2>职责边界</h2>
 * <p>本类只判「签名方<b>集合</b>是否满足规则」，<b>不判签名真伪</b>——验签是调用方的前提。
 * 这样规则可以纯单元测试，而验签依赖链上环境，两者互不拖累。
 *
 * <h2>未决项（与合约一起定）</h2>
 * <ul>
 *   <li>存在多个联邦节点时，需要几个联邦签名；</li>
 *   <li>阈值变更流程；</li>
 *   <li><b>联邦公钥轮换</b>——合约侧若不支持轮换，则轮换只能换合约，在途资金需迁移。
 *       这是硬约束，须在合约设计初期就决定。</li>
 * </ul>
 */
public final class EscrowMultiSigRule {

    /** 多签参与方。 */
    public enum Party {
        /** 联邦仲裁节点（裁决必含）。 */
        FEDERATION,
        /** 买方。 */
        BUYER,
        /** 卖方。 */
        SELLER
    }

    /** 裁决所需的最少签名方数（即 "2/3" 里的 2）。 */
    public static final int REQUIRED_SIGNERS = 2;

    private EscrowMultiSigRule() {
    }

    /**
     * 判定一组签名方是否满足裁决条件。
     *
     * <p>不足阈值或为 {@code null} 时返回 {@code false} 而非抛异常：裁决请求来自外部，
     * 「签名不够」是正常业务结果（应回执"签名不足"），不是程序错误。
     *
     * @param signers 已<b>验签通过</b>的参与方集合（验签是调用方的前提）
     * @return {@code true} = 可执行裁决
     */
    public static boolean satisfies(Set<Party> signers) {
        if (signers == null || signers.size() < REQUIRED_SIGNERS) {
            return false;
        }
        return signers.contains(Party.FEDERATION);
    }
}
