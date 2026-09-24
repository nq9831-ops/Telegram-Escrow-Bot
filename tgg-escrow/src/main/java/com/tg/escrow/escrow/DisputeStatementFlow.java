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

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 争议双方陈述（ET-60）：双方完整陈述 + 确认已读。
 *
 * <h2>三条语义</h2>
 * <ol>
 *   <li><b>仅买卖双方、各一条</b>——完整陈述后不再刷屏；</li>
 *   <li><b>对方确认已读</b>——裁决前双方都看过对方陈述（公平的最低要求）；</li>
 *   <li>双陈述齐 + 双方互认已读 → {@link #complete()} 为 {@code true}，陈述阶段交裁决。</li>
 * </ol>
 *
 * <p>本类只管陈述阶段；证据时限归 {@link EvidenceDeadline}（两窗口独立，08 材料 2.3）。
 * 有状态、非线程安全。
 */
public final class DisputeStatementFlow {

    private final long buyerId;
    private final long sellerId;
    private final Map<Long, String> statements = new HashMap<>();
    private final Set<Long> readBy = new HashSet<>();

    /**
     * @param buyerId  争议订单买方
     * @param sellerId 争议订单卖方
     */
    public DisputeStatementFlow(long buyerId, long sellerId) {
        if (buyerId == sellerId) {
            throw new EscrowException("争议陈述：买方与卖方不得相同");
        }
        this.buyerId = buyerId;
        this.sellerId = sellerId;
    }

    /**
     * 提交陈述（每方限一条）。
     *
     * @param actorId 陈述人（须为买卖双方）
     * @param text    陈述内容（不得空白）
     */
    public void submit(long actorId, String text) {
        requireParty(actorId, "陈述");
        if (text == null || text.isBlank()) {
            throw new EscrowException("争议陈述：内容不得空白");
        }
        if (statements.containsKey(actorId)) {
            throw new EscrowException("争议陈述：每方限一条完整陈述（已提交过）");
        }
        statements.put(actorId, text);
    }

    /**
     * 确认已读<b>对方</b>的陈述。
     *
     * @param readerId  读者（须为买卖双方，且不得读自己）
     * @param authorId  被读陈述的作者
     */
    public void markRead(long readerId, long authorId) {
        requireParty(readerId, "确认已读");
        requireParty(authorId, "被读陈述");
        if (readerId == authorId) {
            throw new EscrowException("争议陈述：只能确认已读对方的陈述");
        }
        if (!statements.containsKey(authorId)) {
            throw new EscrowException("争议陈述：对方尚未陈述，无从已读");
        }
        readBy.add(readerId);
    }

    /** 该方的陈述内容。 */
    public Optional<String> statementOf(long actorId) {
        return Optional.ofNullable(statements.get(actorId));
    }

    /** 陈述阶段是否完成（双方陈述齐 + 双方互认已读）。 */
    public boolean complete() {
        return statements.size() == 2 && readBy.contains(buyerId) && readBy.contains(sellerId);
    }

    private void requireParty(long actorId, String action) {
        if (actorId != buyerId && actorId != sellerId) {
            throw new EscrowException("争议陈述：仅买卖双方可" + action);
        }
    }
}
