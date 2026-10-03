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

import com.tg.escrow.core.PhishingNotice;
import com.tg.escrow.escrow.TraderCreditService;
import com.tg.escrow.escrow.TraderStats;
import com.tg.escrow.escrow.TraderStatsPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 周榜惰性推送（④ 差距 4，ET-77）+ 防钓鱼提醒轮换（GM-13 / ET-54 / ET-69 / ET-71）。
 *
 * <p>不做调度器：靠群内交互触发，每周每群最多一条。提醒文案由 {@link PhishingNotice#noticeOfWeek}
 * 按周轮换——那个类的 javadoc 本就写明「周榜推送用它轮换取文案」，本类就是它的生产消费者。
 *
 * <p>钉住六条边界：只推群（私聊不推）、同周去重、跨周恢复且**文案轮换**、空榜仍推提醒、
 * 默认脱敏口径、ET-80 opt-in 公开者显示 @username（未公开者仍脱敏）。
 */
class WeeklyBoardPushTest {

    private static final long GROUP = -1004497740145L;
    private static final Instant MONDAY_W40 = Instant.parse("2026-09-28T10:00:00Z");
    private static final Instant NEXT_MONDAY_W41 = Instant.parse("2026-10-05T10:00:00Z");

    private static TraderStatsPort statsOf(List<TraderStats> all) {
        return new TraderStatsPort() {
            @Override
            public TraderStats of(long userId) {
                return all.stream().filter(s -> s.userId() == userId).findFirst()
                        .orElse(new TraderStats(userId, 0, 0, 0, 0, 0, null, null, Map.of()));
            }

            @Override
            public List<TraderStats> allWithActivity() {
                return all;
            }
        };
    }

    private static TraderStats trader(long id, int completed) {
        return new TraderStats(id, completed, 0, completed, 0, 0,
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-02T00:00:00Z"),
                Map.of(id + 1000, completed));
    }

    private static WeeklyBoardPush pushAt(Instant now, List<TraderStats> all) {
        return new WeeklyBoardPush(statsOf(all), new TraderCreditService(), Clock.fixed(now, ZoneOffset.UTC), 10);
    }

    /** 该时刻所属 ISO 周序号——提醒文案按它轮换。 */
    private static int isoWeekOf(Instant instant) {
        return instant.atZone(ZoneOffset.UTC)
                .get(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR);
    }

    @Test
    @DisplayName("群里本周首次交互 → 推榜单 + 当周防钓鱼提醒；榜单已匿名")
    void pushesBoardAndNoticeOnceInGroup() {
        WeeklyBoardPush push = pushAt(MONDAY_W40, List.of(trader(1001L, 20), trader(2002L, 10)));

        String text = push.maybePush(GROUP).orElseThrow();

        assertThat(text).contains("本周交易榜").contains("1***1");
        assertThat(text).as("榜单是公开可见的，绝不能出现明文用户 ID").doesNotContain("1001");
        assertThat(text).as("GM-13/ET-54/ET-69/ET-71：提醒随周榜送达")
                .contains("本周提醒").contains(PhishingNotice.noticeOfWeek(isoWeekOf(MONDAY_W40)));
    }

    @Test
    @DisplayName("空榜仍推提醒：防钓鱼是每周该到的东西，不因本周没成交就断供")
    void pushesNoticeEvenWhenBoardEmpty() {
        WeeklyBoardPush push = pushAt(MONDAY_W40, List.of());

        String text = push.maybePush(GROUP).orElseThrow();

        assertThat(text).as("没有成交史时没有榜单可报").doesNotContain("本周交易榜");
        assertThat(text).contains(PhishingNotice.noticeOfWeek(isoWeekOf(MONDAY_W40)));
    }

    @Test
    @DisplayName("同周再交互 → 不再推（每周每群最多一条）")
    void pushesOncePerWeekPerChat() {
        WeeklyBoardPush push = pushAt(MONDAY_W40, List.of(trader(1001L, 20)));

        assertThat(push.maybePush(GROUP)).isPresent();
        assertThat(push.maybePush(GROUP)).as("同周第二次不该再推").isEmpty();
    }

    @Test
    @DisplayName("跨周 → 恢复推送，且提醒文案换成下一条（轮换可复现）")
    void pushesAgainNextWeekWithRotatedNotice() {
        assertThat(pushAt(MONDAY_W40, List.of(trader(1001L, 20))).maybePush(GROUP)).isPresent();
        String week41 = pushAt(NEXT_MONDAY_W41, List.of(trader(1001L, 20))).maybePush(GROUP).orElseThrow();

        assertThat(week41).contains(PhishingNotice.noticeOfWeek(isoWeekOf(NEXT_MONDAY_W41)));
        assertThat(isoWeekOf(NEXT_MONDAY_W41)).as("前提：这两周落在不同的轮换位").isNotEqualTo(isoWeekOf(MONDAY_W40));
    }

    @Test
    @DisplayName("私聊不推（那是噪声，不是服务）")
    void silentInPrivateChat() {
        WeeklyBoardPush push = pushAt(MONDAY_W40, List.of(trader(1001L, 5), trader(2002L, 3)));

        assertThat(push.maybePush(1001L)).isEmpty();
    }

    @Test
    @DisplayName("开关关闭 → 恒静默（bean 仍在，见坑 7）")
    void disabledIsSilent() {
        WeeklyBoardPush off = new WeeklyBoardPush(statsOf(List.of(trader(1001L, 5))),
                new TraderCreditService(), Clock.fixed(MONDAY_W40, ZoneOffset.UTC), 10, false);

        assertThat(off.maybePush(GROUP)).isEmpty();
    }

    @Test
    @DisplayName("ET-80：opt-in 公开者显示 @username，未公开者仍脱敏（与 /escrow rank 同口径）")
    void honorsRankPublicityOptIn() {
        com.tg.escrow.moderation.UserPreferencePort prefs =
                new com.tg.escrow.moderation.UserPreferencePort() {
                    @Override public String noticeModeOf(long userId) {
                        return "all";
                    }
                    @Override public void setNoticeMode(long userId, String mode) {
                    }
                    @Override public com.tg.escrow.moderation.UserPreferencePort.RankVisibility
                            rankVisibilityOf(long userId) {
                        return userId == 1001L
                                ? new com.tg.escrow.moderation.UserPreferencePort.RankVisibility(
                                        true, "alpha_trader")
                                : new com.tg.escrow.moderation.UserPreferencePort.RankVisibility(false, null);
                    }
                    @Override public void setRankPublic(long userId, boolean open, String name) {
                    }
                };
        WeeklyBoardPush push = new WeeklyBoardPush(statsOf(List.of(trader(1001L, 20), trader(2002L, 10))),
                new TraderCreditService(), Clock.fixed(MONDAY_W40, ZoneOffset.UTC), 10, true, prefs);

        String text = push.maybePush(GROUP).orElseThrow();

        assertThat(text).as("公开者按 @username 展示").contains("@alpha_trader");
        assertThat(text).as("未公开者仍脱敏").contains("2***2");
        assertThat(text).as("两种展示都不暴露数字 ID").doesNotContain("1001").doesNotContain("2002");
    }
}
