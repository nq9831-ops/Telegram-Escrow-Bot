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
import com.tg.escrow.escrow.EscrowGroupGuide.Step;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 交易群创建引导（ET-05）的行为固定测试。
 *
 * <p>三步文案流（建群 → 拉机器人+双方 → 开始交易），每步<b>非空且说人话</b>；
 * 进到末步即止（引导不无限延伸）。
 */
class EscrowGroupGuideTest {

    @Test
    @DisplayName("三步文案依次给出，均非空且含操作要点")
    void guideSteps() {
        EscrowGroupGuide g = new EscrowGroupGuide();

        assertThat(g.promptFor(Step.CREATE_GROUP)).isNotBlank().contains("群");
        assertThat(g.promptFor(Step.INVITE)).isNotBlank().contains("机器人");
        assertThat(g.promptFor(Step.START)).isNotBlank().contains("escrow");
    }

    @Test
    @DisplayName("步骤推进：下一步顺序正确，末步之后为空（引导有终点）")
    void stepsAdvance() {
        EscrowGroupGuide g = new EscrowGroupGuide();

        assertThat(g.next(Step.CREATE_GROUP)).hasValue(Step.INVITE);
        assertThat(g.next(Step.START)).isEmpty();
    }

    @Test
    @DisplayName("null 步骤 → fail-closed")
    void nullStepRejected() {
        EscrowGroupGuide g = new EscrowGroupGuide();

        assertThatThrownBy(() -> g.promptFor(null)).isInstanceOf(EscrowException.class);
        assertThatThrownBy(() -> g.next(null)).isInstanceOf(EscrowException.class);
    }
}
