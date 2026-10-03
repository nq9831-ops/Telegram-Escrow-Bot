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

import java.util.Base64;
import java.util.regex.Pattern;

/**
 * TON 地址「能否当作信任锚点」的**唯一判据**。
 *
 * <p>为什么单独成类：它有两个使用者——推导结果（jetton 钱包地址）与配置项（jetton master
 * 地址）。判据散落两处必然漂移，漂移的后果是**某一个入口放行了坏锚点**。与仓库里
 * {@code ChatKind.moderatable()} 的处理同理：判据与它的用途放在一起，不散落。
 *
 * <p>判据三关：非空 → 形态合法（36 字节 friendly，base64url，恰 48 字符）→ 哈希段非全零。
 * <b>第三关是关键</b>：全零地址语法完全合法、可正常解码，但没有任何持有者会匹配它；
 * 只做形态校验的实现会放行它，于是"部署成功、永远收不到钱"。
 */
public final class TonAddresses {

    /** 36 字节 → base64url 无填充 → 恰 48 字符。 */
    private static final Pattern FRIENDLY = Pattern.compile("^[A-Za-z0-9_-]{48}$");

    /** 解码后布局：标记(1) + workchain(1) + 哈希(32) + 校验和(2)。 */
    private static final int RAW_LENGTH = 36;
    private static final int HASH_FROM = 2;
    private static final int HASH_TO = 34;

    private TonAddresses() {
    }

    /**
     * 判断该地址是否可作信任锚点。
     *
     * @return true 仅当：非空、形态合法、且哈希段非全零
     */
    public static boolean isUsableAsAnchor(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        if (!FRIENDLY.matcher(candidate).matches()) {
            return false;
        }
        byte[] raw;
        try {
            raw = Base64.getUrlDecoder().decode(candidate);
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (raw.length != RAW_LENGTH) {
            return false;
        }
        // 校验和：末两字节是前 34 字节的 CRC16/XMODEM。
        // ⚠️ 该算法是**由 ton4j 生成的一个校验和正确的样本反推**得到的（候选集中只有
        // XMODEM(poly 0x1021, init 0) 命中），不是凭记忆写的。
        // 为什么这一关仍必须自己拦：本判据是**纯字符串**函数，服务的是不经过 ton4j 的路径
        // （配置项 tgg.chain.jetton-master、节点回复串）——不能把信任锚点的合法性押在第三方库上。
        // 更正（2026-09-27 实测）：ton4j 2.1.0 的 Address.of **也**校验 CRC，抛的是
        // java.lang.Error("Wrong crc16 hashsum")。原注释写的"ton4j 不校验"与实测相反，已删除。
        int expected = ((raw[RAW_LENGTH - 2] & 0xFF) << 8) | (raw[RAW_LENGTH - 1] & 0xFF);
        if (crc16Xmodem(raw, HASH_TO) != expected) {
            return false;
        }
        for (int i = HASH_FROM; i < HASH_TO; i++) {
            if (raw[i] != 0) {
                return true;
            }
        }
        return false;   // 全零哈希：语法合法，但永远不会匹配到资金
    }

    /** CRC-16/XMODEM（poly 0x1021、init 0x0000、不反射）——TON friendly 地址的校验和。 */
    private static int crc16Xmodem(byte[] data, int len) {
        int crc = 0;
        for (int i = 0; i < len; i++) {
            crc ^= (data[i] & 0xFF) << 8;
            for (int b = 0; b < 8; b++) {
                crc = ((crc & 0x8000) != 0) ? ((crc << 1) ^ 0x1021) & 0xFFFF : (crc << 1) & 0xFFFF;
            }
        }
        return crc;
    }
}
