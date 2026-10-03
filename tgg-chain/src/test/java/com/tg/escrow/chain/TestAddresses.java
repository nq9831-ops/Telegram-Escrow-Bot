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

import org.ton.ton4j.address.Address;

import java.util.Arrays;

/**
 * 测试用地址工厂——**只此一处**。
 *
 * <p>为什么必须由库构造而不是手写串：手写串"看着像地址"，但校验和几乎必然不对。
 * 早先三份测试各自手写常量时，恰恰无法区分"真地址"与"形似地址"，以致共享判据补上
 * CRC16 校验后它们同时被拦下（这正说明判据开始真正生效了）。
 *
 * <p>由 ton4j 构造可保证校验和正确；填充值固定，样本可复现。
 */
final class TestAddresses {

    private TestAddresses() {
    }

    /** 校验和正确的可弹回地址（workchain=0，哈希为固定填充）。 */
    static String valid() {
        byte[] hash = new byte[32];
        Arrays.fill(hash, (byte) 0x5A);
        return Address.of(Address.BOUNCEABLE_TAG, 0, hash).toString(true, true, true);
    }

    /** 另一个校验和正确的地址（填充不同，用于区分"两个不同的合法地址"）。 */
    static String validOther() {
        byte[] hash = new byte[32];
        Arrays.fill(hash, (byte) 0x3C);
        return Address.of(Address.BOUNCEABLE_TAG, 0, hash).toString(true, true, true);
    }
}
