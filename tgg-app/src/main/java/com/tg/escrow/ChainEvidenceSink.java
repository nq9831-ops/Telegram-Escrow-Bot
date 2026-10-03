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

import com.tg.escrow.chain.ChainGateway;
import com.tg.escrow.common.EscrowException;
import com.tg.escrow.escrow.EscrowOrder;
import com.tg.escrow.escrow.EscrowOrderLookupPort;
import com.tg.escrow.escrow.EvidenceHasher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;

/**
 * 链上存证腿（ET-43 双通道的第二条腿）：订单地址 → 加盐哈希 → {@code RecordEvidence} 上链。
 *
 * <h2>失败即抛（由 {@code EvidenceChannel} 捕获转状态）</h2>
 * <p>本类只做"尝试"：任何一步不成立都抛异常（订单不存在 / 未部署合约 / 盐缺失 / 节点不可达），
 * 由 {@code EvidenceChannel} 的 trySave 转成 {@code chainSaved=false} 如实回报。
 * <b>绝不静默成功</b>——先前的空实现（不抛的 lambda）会被 trySave 当作成功，
 * 让回执把"未上链"报成"已上链"——那个 bug 的教训就写在 {@code evidenceChannelFor} 的注释里。
 *
 * <h2>上传的是什么</h2>
 * <p>证据的<b>加盐哈希</b>（SHA-256 hex → uint256），不是原文——链上只留指纹；
 * 原文留在链下的另一条腿（群内留痕）。盐与日志脱敏共用 {@code tgg.log.salt}
 * （同一个部署级秘密，不新开配置面）；未配置时构造期 warn（启动可见），
 * 真正调用时 {@link EvidenceHasher#saltedHash} 会拒绝无盐哈希（fail-closed）。
 */
public final class ChainEvidenceSink {

    private static final Logger log = LoggerFactory.getLogger(ChainEvidenceSink.class);

    private final EscrowOrderLookupPort lookup;
    private final ChainGateway gateway;
    private final String salt;

    public ChainEvidenceSink(EscrowOrderLookupPort lookup, ChainGateway gateway, String salt) {
        if (lookup == null || gateway == null) {
            throw new EscrowException("链上存证：订单查询与链上入口均不可为空");
        }
        if (salt == null || salt.isBlank()) {
            log.warn("链上存证：tgg.log.salt（TGG_LOG_SALT）未配置——证据哈希将无盐"
                    + "（无盐哈希可被字典反推，且提交会被拒绝）。请与日志脱敏盐一并配置。");
        }
        this.lookup = lookup;
        this.gateway = gateway;
        this.salt = salt;
    }

    /**
     * 把一条证据的加盐哈希记进该订单的链上合约。
     *
     * @param orderId  订单号（其链上合约地址是投递目标）
     * @param evidence 证据原文（只上链指纹，原文不离开本机）
     * @throws EscrowException 订单不存在 / 未部署链上合约 / 盐缺失
     * @throws com.tg.escrow.chain.ChainUnavailableException 写链失败
     */
    public void save(long orderId, String evidence) {
        EscrowOrder order = lookup.byId(orderId)
                .orElseThrow(() -> new EscrowException("链上存证：订单 #" + orderId + " 不存在"));
        String address = order.getChainContractAddress();
        if (address == null || address.isBlank()) {
            throw new EscrowException("链上存证：订单 #" + orderId
                    + " 未部署链上合约（经 /admin/chain/deploy 部署后方可上链）");
        }
        String saltedHash = EvidenceHasher.saltedHash(evidence, salt);
        gateway.recordEvidence(address, new BigInteger(saltedHash, 16));
    }
}
