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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 资金流向验证推送（ET-59/70）的行为固定测试。
 *
 * <p>文案两条硬要求：①含交易哈希与「可自行验证」（ET-59 用户可核）；
 * ②含<b>官方声明句</b>（不通过评论发放奖励——ET-70 钓鱼防御，随推送周知）。
 */
class ChainVerificationNoticeTest {

    @Test
    @DisplayName("文案含交易哈希与可验证指引")
    void containsTxHash() {
        String notice = ChainVerificationNotice.forTx("abc123def456");

        assertThat(notice).contains("abc123def456").contains("验证");
    }

    @Test
    @DisplayName("文案含官方声明句（防 Comment 钓鱼，ET-70）")
    void containsOfficialDisclaimer() {
        String notice = ChainVerificationNotice.forTx("abc123def456");

        assertThat(notice).contains("评论").contains("奖励");
    }

    @Test
    @DisplayName("空白交易哈希 → fail-closed")
    void blankHashRejected() {
        assertThatThrownBy(() -> ChainVerificationNotice.forTx(""))
                .isInstanceOf(EscrowException.class);
        assertThatThrownBy(() -> ChainVerificationNotice.forTx(null))
                .isInstanceOf(EscrowException.class);
    }
}
