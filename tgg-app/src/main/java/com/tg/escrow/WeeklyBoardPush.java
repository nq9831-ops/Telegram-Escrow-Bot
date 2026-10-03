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

import com.tg.escrow.escrow.Leaderboard;
import com.tg.escrow.escrow.RankPrivacy;
import com.tg.escrow.escrow.TraderCredit;
import com.tg.escrow.escrow.TraderCreditService;
import com.tg.escrow.escrow.TraderStatsPort;
import com.tg.escrow.moderation.UserPreferencePort;

import java.time.Clock;
import java.time.temporal.IsoFields;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 周榜惰性推送（④ 差距 4，ET-77）：<b>不做调度器</b>，靠群内交互触发。
 *
 * <h2>为什么是"惰性"</h2>
 * <p>项目既定非目标是引入调度器（超时/榜单都不自动执行）。没有定时器时，榜单只能搭在
 * 「群里本来就会发生的交互」上——某个自然周内第一次有群交互时，顺手把榜单发出来。
 * 于是每周最多推一条，且完全不需要后台线程。
 *
 * <h2>三条边界</h2>
 * <ul>
 *   <li><b>只推群</b>：私聊不推（那是噪声，不是服务）；</li>
 *   <li><b>每周每群最多一条</b>：按 ISO 周去重，同周重复交互不再推；</li>
 *   <li><b>空榜不推</b>：没人有成交史时没有可推的内容，静默返回（不推一条"暂无数据"占屏）。</li>
 * </ul>
 *
 * <h2>已知边界（如实标注，不假装精确）</h2>
 * <p>去重状态在<b>进程内</b>（{@link ConcurrentHashMap}）：重启后同一周可能再推一次。要做到
 * 跨重启精确一次需要一张持久表 + Flyway 迁移——对"每周最多多推一条"的收益，本波判定不值；
 * 若将来榜单变成运营指标，正确的下一步是物化榜单表（见 {@code TraderStatsPort} 注释）。
 *
 * <p>榜单口径与 {@code /escrow rank} <b>完全同源</b>（同一 {@link TraderCreditService#rank}
 * 与同一公开口径渲染：默认脱敏、ET-80 opt-in 公开者显示 @username），不另写一套。
 */
public final class WeeklyBoardPush {

    private final TraderStatsPort statsPort;
    private final TraderCreditService credit;
    private final Clock clock;
    private final int topN;

    /** 运营开关（{@code tgg.credit.weekly-push}）；false 时本类恒为静默。 */
    private final boolean enabled;

    /** ET-80：榜单公开偏好；{@code null} 时回退强制匿名（旧行为，便于存量测试）。 */
    private final UserPreferencePort prefs;

    /** 群 → 最近一次推送的 ISO 周键（如 {@code 2026-W40}）。 */
    private final Map<Long, String> lastPushedWeek = new ConcurrentHashMap<>();

    /**
     * @param statsPort 参与者统计来源
     * @param credit    评分器（与 /escrow rank 同一个 bean）
     * @param clock     时钟（决定"这是第几周"）
     * @param topN      榜单条数（≥1）
     */
    public WeeklyBoardPush(TraderStatsPort statsPort, TraderCreditService credit, Clock clock, int topN) {
        this(statsPort, credit, clock, topN, true);
    }

    /**
     * @param enabled 运营开关（{@code tgg.credit.weekly-push}）——关闭时 {@link #maybePush} 恒返回空，
     *                但 bean 依然存在（让 @Bean 返回 null 会让注入方起不来，见坑 7）
     */
    public WeeklyBoardPush(TraderStatsPort statsPort, TraderCreditService credit, Clock clock,
                           int topN, boolean enabled) {
        this(statsPort, credit, clock, topN, enabled, null);
    }

    /**
     * @param prefs ET-80 公开偏好；{@code null} 时推送榜回退强制匿名（与旧行为一致）
     */
    public WeeklyBoardPush(TraderStatsPort statsPort, TraderCreditService credit, Clock clock,
                           int topN, boolean enabled, UserPreferencePort prefs) {
        if (statsPort == null || credit == null || clock == null) {
            throw new com.tg.escrow.common.TggException("周榜推送：统计端口/评分器/时钟均不可为空");
        }
        if (topN < 1) {
            throw new com.tg.escrow.common.TggException("周榜推送：条数必须 ≥1，实为 " + topN);
        }
        this.statsPort = statsPort;
        this.credit = credit;
        this.clock = clock;
        this.topN = topN;
        this.enabled = enabled;
        this.prefs = prefs;
    }

    /**
     * 若本群本周尚未推过、且榜上有内容 → 返回要发出去的榜单文案并记账；否则返回空。
     *
     * @param chatId 触发交互的会话（{@code ≥0} 表示私聊 → 永不推送）
     */
    public Optional<String> maybePush(long chatId) {
        if (!enabled || chatId >= 0) {
            return Optional.empty();
        }
        String week = weekKey(clock.instant());
        if (week.equals(lastPushedWeek.get(chatId))) {
            return Optional.empty();
        }
        lastPushedWeek.put(chatId, week);
        return Optional.of(compose(week));
    }

    /**
     * 组装本周推送内容：榜单（有内容时）+ 当周防钓鱼提醒。
     *
     * <h2>为什么空榜也推</h2>
     * <p>提醒（GM-13 / ET-54 / ET-69 / ET-71）是<b>每周该到的东西</b>，不因本周没成交就断供——
     * 防钓鱼的价值恰恰在"还没出事的时候"。故榜单为空时只发提醒，不发"暂无数据"占屏。
     *
     * <p>提醒文案由 {@link com.tg.escrow.core.PhishingNotice#noticeOfWeek} 按周轮换：
     * 那个类的 javadoc 在本波之前就写明「周榜推送用它轮换取文案」——本类即它的生产消费者。
     */
    private String compose(String week) {
        StringBuilder sb = new StringBuilder();
        List<TraderCredit> ranked = credit.rank(statsPort.allWithActivity(), topN);
        if (!ranked.isEmpty()) {
            List<Leaderboard.Entry> entries = ranked.stream()
                    .map(c -> {
                        // prefs 为 null（旧装配/测试）时回退强制匿名；否则与 /escrow rank 同一口径。
                        if (prefs == null) {
                            return new Leaderboard.Entry(
                                    RankPrivacy.display(String.valueOf(c.userId()), false),
                                    c.score(), c.tier(), false);
                        }
                        UserPreferencePort.RankVisibility v = prefs.rankVisibilityOf(c.userId());
                        boolean open = v.publicProfile()
                                && v.username() != null && !v.username().isBlank();
                        // @ 前缀与 /escrow rank、publish 回执的口径一致。
                        String label = open ? "@" + v.username() : String.valueOf(c.userId());
                        return new Leaderboard.Entry(label, c.score(), c.tier(), open);
                    })
                    .toList();
            // prefs==null 走旧的强制匿名；否则逐人按公开设置渲染（默认仍脱敏）。
            sb.append("📅 本周交易榜\n")
                    .append(Leaderboard.render(entries, topN, prefs == null))
                    .append('\n');
        }
        sb.append("🛡 本周提醒：").append(com.tg.escrow.core.PhishingNotice.noticeOfWeek(weekOfYear()));
        return sb.toString();
    }

    /** 当前 ISO 周序号（1–53）——提醒按它轮换，同一周恒取同一条（可复现）。 */
    private int weekOfYear() {
        return clock.instant().atZone(java.time.ZoneOffset.UTC)
                .get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
    }

    /** ISO 周键（跨年边界按 ISO 定义，不用自然年——12 月末的一周不会被拆成两段）。 */
    static String weekKey(java.time.Instant instant) {
        java.time.ZonedDateTime utc = instant.atZone(java.time.ZoneOffset.UTC);
        return utc.get(IsoFields.WEEK_BASED_YEAR) + "-W" + utc.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
    }
}
