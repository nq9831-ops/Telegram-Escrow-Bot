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

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 裁决单规范序列化的行为固定测试。
 *
 * <p>为什么这层值得单独测：签名是对<b>字节</b>的签名。若同一份裁决有两种字节表示
 * （例如理由里含换行导致字段边界漂移），那么一方按 A 表示签名、另一方按 B 表示验签，
 * 就会得出"签名不通过"——而双方其实意见一致。这类故障在联调时表现为随机的验签失败，
 * 极难定位，所以格式必须被测试钉死。
 */
class EscrowVerdictTest {

    private static final long ORDER_ID = 42L;
    private static final Instant ISSUED_AT = Instant.parse("2026-09-23T10:00:00Z");

    private static EscrowVerdict release(String reason) {
        return new EscrowVerdict(ORDER_ID, EscrowVerdict.Outcome.RELEASE, reason, ISSUED_AT);
    }

    @Test
    @DisplayName("同一裁决两次序列化结果完全相同（确定性）")
    void serializationIsDeterministic() {
        assertThat(release("卖方已交付").canonicalBytes())
                .isEqualTo(release("卖方已交付").canonicalBytes());
    }

    @Test
    @DisplayName("序列化带版本前缀——将来格式变更可被识别而非静默错配")
    void carriesVersionPrefix() {
        String text = new String(release("理由").canonicalBytes(), StandardCharsets.UTF_8);

        assertThat(text).startsWith("escrow-verdict/v1\n");
    }

    @Test
    @DisplayName("字段任一变化 → 字节变化（订单号 / 结果 / 理由 / 时间）")
    void anyFieldChangeAltersBytes() {
        byte[] base = release("理由").canonicalBytes();

        assertThat(new EscrowVerdict(43L, EscrowVerdict.Outcome.RELEASE, "理由", ISSUED_AT)
                .canonicalBytes()).isNotEqualTo(base);
        assertThat(new EscrowVerdict(ORDER_ID, EscrowVerdict.Outcome.REFUND, "理由", ISSUED_AT)
                .canonicalBytes()).isNotEqualTo(base);
        assertThat(new EscrowVerdict(ORDER_ID, EscrowVerdict.Outcome.RELEASE, "理由2", ISSUED_AT)
                .canonicalBytes()).isNotEqualTo(base);
        assertThat(new EscrowVerdict(ORDER_ID, EscrowVerdict.Outcome.RELEASE, "理由",
                ISSUED_AT.plusSeconds(1)).canonicalBytes()).isNotEqualTo(base);
    }

    @Test
    @DisplayName("理由含换行/冒号也不产生歧义——不同裁决永不撞字节")
    void reasonWithSeparatorsIsNotAmbiguous() {
        // 若用换行做分隔且不转义，这两者会撞成同一串字节：
        //   reason="a\noutcome=RELEASE" 与 reason="a" + 下一字段被顶走
        Set<String> encoded = new HashSet<>();
        encoded.add(asText(release("a\nREFUND")));
        encoded.add(asText(release("a")));
        encoded.add(asText(release("a\n")));
        encoded.add(asText(release("")));

        assertThat(encoded).as("四种不同理由必须产生四种不同字节").hasSize(4);
    }

    @Test
    @DisplayName("空理由是合法的（裁决可以没有附加说明）")
    void emptyReasonIsAllowed() {
        assertThat(release("").canonicalBytes()).isNotEmpty();
        assertThat(release("").canonicalBytes()).isNotEqualTo(release(" ").canonicalBytes());
    }

    @Test
    @DisplayName("理由为 null → 抛 EscrowException（不静默当作空串，避免两种意图混同）")
    void nullReasonIsRejected() {
        assertThatThrownBy(() -> release(null))
                .isInstanceOf(com.tg.escrow.common.EscrowException.class);
    }

    @Test
    @DisplayName("订单号非正数 → 抛异常（0 或负数不可能是真实订单）")
    void nonPositiveOrderIdIsRejected() {
        assertThatThrownBy(() -> new EscrowVerdict(0L, EscrowVerdict.Outcome.RELEASE, "", ISSUED_AT))
                .isInstanceOf(com.tg.escrow.common.EscrowException.class);
        assertThatThrownBy(() -> new EscrowVerdict(-1L, EscrowVerdict.Outcome.RELEASE, "", ISSUED_AT))
                .isInstanceOf(com.tg.escrow.common.EscrowException.class);
    }

    @Test
    @DisplayName("结果为 null 或时间缺失 → 抛异常")
    void nullOutcomeOrInstantIsRejected() {
        assertThatThrownBy(() -> new EscrowVerdict(ORDER_ID, null, "", ISSUED_AT))
                .isInstanceOf(com.tg.escrow.common.EscrowException.class);
        assertThatThrownBy(() -> new EscrowVerdict(ORDER_ID, EscrowVerdict.Outcome.RELEASE, "", null))
                .isInstanceOf(com.tg.escrow.common.EscrowException.class);
    }

    @Test
    @DisplayName("时间精度到秒——同一秒内的两次裁决视为同一份（签名是逐字节的，亚秒差异会让双方对不上）")
    void issuedAtIsTruncatedToSeconds() {
        EscrowVerdict withNanos = new EscrowVerdict(ORDER_ID, EscrowVerdict.Outcome.RELEASE, "理由",
                Instant.parse("2026-09-23T10:00:00.123456Z"));

        assertThat(withNanos.canonicalBytes())
                .isEqualTo(new EscrowVerdict(ORDER_ID, EscrowVerdict.Outcome.RELEASE, "理由",
                        Instant.parse("2026-09-23T10:00:00Z")).canonicalBytes());
    }

    private static String asText(EscrowVerdict verdict) {
        return new String(verdict.canonicalBytes(), StandardCharsets.UTF_8);
    }
}
