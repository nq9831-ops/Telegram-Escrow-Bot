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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 新手标识与互刷豁免（ET-38，业务冲突 2.1 的解法）的行为固定测试。
 *
 * <p>两条规则同时钉住：①<b>前 3 笔标"新手"</b>；②<b>前 3 笔即便同一对手也计信用、
 * 不触发互刷检测</b>——否则新用户与唯一交易对象的前几笔会被误判为互刷，信用冷启动直接冻结。
 * 边界定死：完成数 {@code < 3} 为新手/豁免（"前 3 笔"= 第 1、2、3 笔交易本身）。
 */
class NewcomerBadgeTest {

    @Test
    @DisplayName("完成 0/1/2 笔 → 新手；第 3 笔完成后不再是新手")
    void newcomerUpToThree() {
        assertThat(NewcomerBadge.isNewcomer(0)).isTrue();
        assertThat(NewcomerBadge.isNewcomer(2)).isTrue();
        assertThat(NewcomerBadge.isNewcomer(3)).isFalse();
    }

    @Test
    @DisplayName("互刷豁免：前 3 笔（完成 < 3）即便同一对手也不触发互刷检测")
    void mutualBrushExemptForFirstThree() {
        assertThat(NewcomerBadge.isMutualBrushExempt(0)).isTrue();
        assertThat(NewcomerBadge.isMutualBrushExempt(2)).isTrue();
        assertThat(NewcomerBadge.isMutualBrushExempt(3)).isFalse();
    }

    @Test
    @DisplayName("完成 ≥ 3 笔后进入互刷检测（老手无豁免）")
    void veteransNotExempt() {
        assertThat(NewcomerBadge.isMutualBrushExempt(50)).isFalse();
        assertThat(NewcomerBadge.isNewcomer(50)).isFalse();
    }

    @Test
    @DisplayName("负完成数 → 抛（数据异常 fail-closed）")
    void negativeCountRejected() {
        assertThatThrownBy(() -> NewcomerBadge.isNewcomer(-1))
                .isInstanceOf(com.tg.escrow.common.EscrowException.class);
    }
}
