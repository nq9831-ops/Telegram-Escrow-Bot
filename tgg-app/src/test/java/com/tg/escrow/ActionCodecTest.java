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
package com.tg.escrow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 按钮回调编解码（`callback_data` ↔ 等价命令文本）。
 *
 * <h2>为什么是"命令文本"而不是某种新动作模型</h2>
 * <p>按钮必须与命令<b>严格等价</b>——否则同一动作会有两条语义路径，权限守卫、状态守卫、
 * 文案披露都可能在其中一条上漂移。解码成一条普通命令文本，就能原样复用既有分发与守卫链。
 *
 * <h2>两个方向的不对称（刻意）</h2>
 * <ul>
 *   <li>{@code encode} 是<b>内部</b>调用（我们自己造按钮）——参数非法即 bug，故 fail-fast；</li>
 *   <li>{@code decode} 吃的是<b>外部</b>数据（旧消息里的按钮可能属于已改版的编码），
 *       畸形输入返回 {@code empty} 而非抛——它绝不能把更新处理线程打崩。</li>
 * </ul>
 */
class ActionCodecTest {

    @Test
    @DisplayName("往返：encode(verb, id) ↔ decode 成等价命令文本")
    void roundTrip() {
        assertThat(ActionCodec.encode("rl", 3)).isEqualTo("rl:3");
        assertThat(ActionCodec.decode("rl:3")).contains("/escrow release 3");
    }

    @Test
    @DisplayName("六个动作 verb 全部映射到对应子命令")
    void allVerbsMapToSubcommands() {
        assertThat(ActionCodec.decode("st:1")).contains("/escrow status 1");
        assertThat(ActionCodec.decode("lk:1")).contains("/escrow lock 1");
        assertThat(ActionCodec.decode("dl:1")).contains("/escrow deliver 1");
        assertThat(ActionCodec.decode("rl:1")).contains("/escrow release 1");
        assertThat(ActionCodec.decode("rf:1")).contains("/escrow refund 1");
        assertThat(ActionCodec.decode("dp:1")).contains("/escrow dispute 1");
    }

    @Test
    @DisplayName("三段确认形态：encode(verb,id,true) ↔ decode 出带 --yes 的等价命令")
    void confirmRoundTrip() {
        assertThat(ActionCodec.encode("rl", 5)).isEqualTo("rl:5");
        assertThat(ActionCodec.encode("rl", 5, true)).isEqualTo("rl:5:y");
        assertThat(ActionCodec.encode("rf", 5, true)).isEqualTo("rf:5:y");
        assertThat(ActionCodec.decode("rl:5:y")).contains("/escrow release 5 --yes");
        assertThat(ActionCodec.decode("rf:5:y")).contains("/escrow refund 5 --yes");
    }

    @Test
    @DisplayName("确认三段：仅末段逐字 y 合法，其余一律 empty（放宽成任意尾巴=确认环被绕过）")
    void confirmSegmentStrictness() {
        assertThat(ActionCodec.decode("rl:3:x")).isEmpty();
        assertThat(ActionCodec.decode("rl:3:y:z")).isEmpty();
        assertThat(ActionCodec.decode("rl:3:Y")).isEmpty();   // 大写不算
        assertThat(ActionCodec.decode("rl:3:")).isEmpty();     // 空标记段
        assertThat(ActionCodec.decode("rl:y")).isEmpty();      // 标记占了订单号位
        assertThat(ActionCodec.decode(":3:y")).isEmpty();      // 无 verb 段
        assertThat(ActionCodec.decode("xx:3:y")).isEmpty();    // 未知 verb
        assertThat(ActionCodec.decode("rl:0:y")).isEmpty();    // 订单号必须为正
    }

    @Test
    @DisplayName("畸形/未知输入 → empty（不抛）——按钮数据来自外部，不能让它把分发打崩")
    void malformedInputIsEmptyNotThrow() {
        assertThat(ActionCodec.decode(null)).isEmpty();
        assertThat(ActionCodec.decode("")).isEmpty();
        assertThat(ActionCodec.decode("rl")).isEmpty();       // 无分隔符
        assertThat(ActionCodec.decode("xx:3")).isEmpty();     // 未知 verb
        assertThat(ActionCodec.decode("rl:abc")).isEmpty();   // id 非数字
        assertThat(ActionCodec.decode("rl:0")).isEmpty();     // id 必须为正
        assertThat(ActionCodec.decode("rl:-1")).isEmpty();
        assertThat(ActionCodec.decode("rl:3:9")).isEmpty();   // 多余段
    }

    @Test
    @DisplayName("encode 非法参数 → fail-fast（编码方是内部调用，错即 bug）")
    void encodeRejectsBadInput() {
        assertThatThrownBy(() -> ActionCodec.encode("xx", 1)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> ActionCodec.encode("rl", 0)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> ActionCodec.encode(null, 1)).isInstanceOf(RuntimeException.class);
    }
}
