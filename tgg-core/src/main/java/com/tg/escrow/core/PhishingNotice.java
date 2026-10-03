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

import com.tg.escrow.common.TggException;

import java.util.List;

/**
 * 防钓鱼提示库（GM-13 / ET-54 / ET-71——历史材料三处编号，此处合并为一处定义）。
 *
 * <p>三类攻击各有警示（来源 SPEC 6.3、05/09 材料）：
 * <ul>
 *   <li><b>账号劫持</b>：要求紧急转移/点链接/给验证码的都是被盗；</li>
 *   <li><b>Comment 钓鱼</b>：非主动发起的"收到 USDT"提示都是诈骗；</li>
 *   <li><b>Permit Signature 授权钓鱼</b>（SPF 2026-07 警示）：看到"授权使用代币/无限额度"
 *       的签名请求立即终止——链下授权签名可被事后免批准转走资产。</li>
 * </ul>
 *
 * <p>周榜推送用 {@link #noticeOfWeek} 轮换取文案：模运算保证<b>同一周恒取同一条</b>（可复现）。
 * 纯常量 + 纯函数。
 */
public final class PhishingNotice {

    /** 账号劫持警示。 */
    public static final String ACCOUNT_HIJACK =
            "官方不会要求你紧急转移资产、点击陌生链接或提供验证码——这类要求都可能是账号被盗。";

    /** Comment 钓鱼警示。 */
    public static final String COMMENT_PHISHING =
            "评论区里非你主动发起的「收到 USDT」提示都可能是诈骗，官方不通过评论发放奖励。";

    /** Permit Signature 授权签名警示。 */
    public static final String PERMIT_SIGNATURE =
            "签名前请核对内容：凡是要求「授权使用代币」或「无限额度」的签名请求，立即终止——"
                    + "链下授权签名之后，对方可不经你再次批准转走资产。";

    private static final List<String> NOTICES =
            List.of(ACCOUNT_HIJACK, COMMENT_PHISHING, PERMIT_SIGNATURE);

    private PhishingNotice() {
    }

    /** 全部警示文案（三类）。 */
    public static List<String> allNotices() {
        return NOTICES;
    }

    /**
     * 按周轮换取一条警示（同一周恒取同一条，模运算回绕）。
     *
     * @param week 周序号（不得为负）
     */
    public static String noticeOfWeek(int week) {
        if (week < 0) {
            throw new TggException("防钓鱼提示：周序号不得为负，实为 " + week);
        }
        return NOTICES.get(week % NOTICES.size());
    }
}
