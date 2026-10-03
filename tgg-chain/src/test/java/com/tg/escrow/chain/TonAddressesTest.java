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
import org.ton.ton4j.address.Address;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TON 地址「能否当信任锚点」的共享判据测试。
 *
 * <p>为什么要把判据抽成一处：它现在有两个使用者——推导结果（jetton 钱包地址）与配置项
 * （jetton master 地址）。两处各写一份必然漂移，而漂移的后果是**某个入口放行了坏锚点**。
 *
 * <p>最要命的两条：
 * <ul>
 *   <li><b>全零地址</b>：48 字符、语法完全合法、可解码，但没有任何持有者会匹配它；</li>
 *   <li><b>校验和错误的地址</b>：只差一个字符的笔误也长着合法外形（48 字符 base64url），
 *       只查形态与全零的实现会**放行它**——写进合约就是"看着配好了、永远收不到钱"。</li>
 * </ul>
 * 正例地址由 ton4j 自己构造（保证校验和正确），避免手写串把测试带偏。
 */
class TonAddressesTest {

    /** 用 ton4j 构造一个校验和正确的非零地址（tag=可弹回、workchain=0、32 字节非零哈希）。 */
    private static String validAddress() {
        byte[] hash = new byte[32];
        Arrays.fill(hash, (byte) 0x5A);
        return Address.of(Address.BOUNCEABLE_TAG, 0, hash).toString(true, true, true);
    }

    private static final String ZERO = "EQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAM9c";

    /** 把合法地址的最后一个字符换掉——校验和必然对不上，但外形仍然合法。 */
    private static String corruptLastChar(String address) {
        char last = address.charAt(address.length() - 1);
        char swapped = (last == 'A') ? 'B' : 'A';
        return address.substring(0, address.length() - 1) + swapped;
    }

    @Test
    @DisplayName("形态合法、校验和正确、哈希非零 → 可作锚点")
    void acceptsUsableAddress() {
        assertThat(TonAddresses.isUsableAsAnchor(validAddress())).isTrue();
    }

    @Test
    @DisplayName("空 / 空白 / null → 不可作锚点")
    void rejectsBlank() {
        assertThat(TonAddresses.isUsableAsAnchor(null)).isFalse();
        assertThat(TonAddresses.isUsableAsAnchor("")).isFalse();
        assertThat(TonAddresses.isUsableAsAnchor("   ")).isFalse();
    }

    @Test
    @DisplayName("形态非法（长度/字符集）→ 不可作锚点")
    void rejectsMalformed() {
        assertThat(TonAddresses.isUsableAsAnchor("not-an-address")).isFalse();
        assertThat(TonAddresses.isUsableAsAnchor(
                "EQD9dXo0Vz0kI7p3S2m1nJ4qK8cV0pXbG5tR6yU7wZ8aB1c")).isFalse();   // 少一位
        assertThat(TonAddresses.isUsableAsAnchor(
                "EQD9dXo0Vz0kI7p3S2m1nJ4qK8cV0pXbG5tR6yU7wZ8aB1c!")).isFalse();  // 非法字符
    }

    @Test
    @DisplayName("全零地址 → 不可作锚点（语法合法但永远匹配不到）")
    void rejectsZeroAddress() {
        assertThat(TonAddresses.isUsableAsAnchor(ZERO)).isFalse();
    }

    @Test
    @DisplayName("校验和错误的地址 → 不可作锚点（一个字符的笔误也必须拦住）")
    void rejectsBadChecksum() {
        String corrupted = corruptLastChar(validAddress());

        assertThat(corrupted).hasSize(48);                        // 形态仍然合法，只有校验和不对
        assertThat(TonAddresses.isUsableAsAnchor(corrupted)).isFalse();
    }
}
