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

import com.tg.escrow.common.EscrowException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 证据哈希（ET-51/57：链上存证 + 预映像防护）——<b>加盐 SHA-256</b>。
 *
 * <p>交付证明哈希上链前必须加盐（SPEC 6.2）：无盐哈希的输入空间小（常见短语、单号格式），
 * 攻击者可预计算字典反推原文。盐与证据拼接后哈希，链上存的是 {@code hash(salt ++ ":" ++ evidence)}
 * （与 {@code EscrowContract.tolk} 的 {@code handleConfirm} 约定一致）。纯函数。
 */
public final class EvidenceHasher {

    private EvidenceHasher() {
    }

    /**
     * 计算加盐证据哈希（SHA-256 hex，64 字符）。
     *
     * @param evidence 证据内容（如物流单号、IPFS 哈希）
     * @param salt     部署/交易级盐值（<b>必填</b>——无盐等于没防）
     */
    public static String saltedHash(String evidence, String salt) {
        if (evidence == null || evidence.isBlank()) {
            throw new EscrowException("证据哈希：证据内容不可为空");
        }
        if (salt == null || salt.isBlank()) {
            throw new EscrowException("证据哈希：盐值必须提供（无盐哈希可被字典反推）");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((salt + ":" + evidence).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException ex) {
            throw new EscrowException("运行环境缺少 SHA-256——拒绝以降级方式输出证据哈希", ex);
        }
    }
}
