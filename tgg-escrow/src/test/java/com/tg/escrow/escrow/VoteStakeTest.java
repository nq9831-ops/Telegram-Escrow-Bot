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
import com.tg.escrow.escrow.VoteStake.State;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 投票质押（VoteStake）的行为固定测试。
 *
 * <p>核心规则：质押<b>锁定 → 没收 / 退还</b>三态，终态不可再动；金额范围 <b>1–5 USDT</b>
 * （核心规则「仲裁员质押 1-5 USDT」）。金额越界与非法迁移都必须 fail-closed——
 * 质押是真金白银的托管，状态漂移即资金漂移。
 */
class VoteStakeTest {

    private static final long VOTER = 555L;
    private static final Instant T0 = Instant.parse("2026-09-23T00:00:00Z");

    private static VoteStake stake(String amount) {
        return new VoteStake(VOTER, new BigDecimal(amount), T0);
    }

    @Test
    @DisplayName("新建即锁定（LOCKED），金额与投票人原样保留")
    void startsLocked() {
        VoteStake s = stake("3");

        assertThat(s.currentState()).isEqualTo(State.LOCKED);
        assertThat(s.voterId()).isEqualTo(VOTER);
        assertThat(s.amount()).isEqualByComparingTo("3");
    }

    @Test
    @DisplayName("投错 → 没收（LOCKED → FORFEITED）")
    void forfeit() {
        VoteStake s = stake("1");
        s.forfeit(T0.plusSeconds(1));

        assertThat(s.currentState()).isEqualTo(State.FORFEITED);
    }

    @Test
    @DisplayName("投对/退出 → 退还（LOCKED → RETURNED）")
    void returned() {
        VoteStake s = stake("5");
        s.returnToVoter(T0.plusSeconds(1));

        assertThat(s.currentState()).isEqualTo(State.RETURNED);
    }

    @Test
    @DisplayName("金额范围 1–5 USDT：边界含两端，越界拒绝")
    void amountRange() {
        assertThat(stake("1").currentState()).isEqualTo(State.LOCKED);
        assertThat(stake("5").currentState()).isEqualTo(State.LOCKED);
        assertThatThrownBy(() -> stake("0.5")).isInstanceOf(EscrowException.class);
        assertThatThrownBy(() -> stake("5.01")).isInstanceOf(EscrowException.class);
    }

    @Test
    @DisplayName("终态 FORFEITED 不可退还；终态 RETURNED 不可没收")
    void terminalStatesAreFinal() {
        VoteStake f = stake("2");
        f.forfeit(T0);
        assertThatThrownBy(() -> f.returnToVoter(T0)).isInstanceOf(EscrowException.class);

        VoteStake r = stake("2");
        r.returnToVoter(T0);
        assertThatThrownBy(() -> r.forfeit(T0)).isInstanceOf(EscrowException.class);
    }

    @Test
    @DisplayName("金额为 null → 构造期拒绝")
    void nullAmountRejected() {
        assertThatThrownBy(() -> new VoteStake(VOTER, null, T0))
                .isInstanceOf(EscrowException.class);
    }
}
