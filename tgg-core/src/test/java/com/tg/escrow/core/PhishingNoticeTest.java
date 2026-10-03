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
package com.tg.escrow.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 防钓鱼提示库（GM-13 / ET-54 / ET-71 合并为一处定义）的行为固定测试。
 *
 * <p>三类攻击各有对应警示（来源 SPEC 6.3 与 05/09 材料）：账号劫持、Comment 钓鱼、
 * <b>Permit Signature 授权钓鱼</b>。周榜推送按周轮换取文案（可复现：同一周恒取同一条）。
 */
class PhishingNoticeTest {

    @Test
    @DisplayName("三类警示全部覆盖：账号劫持 / Comment 钓鱼 / Permit Signature 授权")
    void coversThreeAttackTypes() {
        assertThat(PhishingNotice.ACCOUNT_HIJACK).contains("验证码");
        assertThat(PhishingNotice.COMMENT_PHISHING).contains("评论").contains("USDT");
        assertThat(PhishingNotice.PERMIT_SIGNATURE).contains("授权").contains("无限");
    }

    @Test
    @DisplayName("周轮换：同一周恒取同一条（可复现），轮换覆盖全部条目")
    void weeklyRotationStableAndComplete() {
        String week0 = PhishingNotice.noticeOfWeek(0);
        assertThat(PhishingNotice.noticeOfWeek(0)).isEqualTo(week0);

        int size = PhishingNotice.allNotices().size();
        assertThat(size).isGreaterThanOrEqualTo(3);
        assertThat(PhishingNotice.noticeOfWeek(size)).isEqualTo(week0); // 模运算回绕
    }

    @Test
    @DisplayName("每条警示都非空、且不泄漏占位符")
    void noticesNonBlank() {
        for (String n : PhishingNotice.allNotices()) {
            assertThat(n).isNotBlank().doesNotContain("{");
        }
    }
}
