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

import com.tg.escrow.core.BotCommand;
import com.tg.escrow.core.CommandActor;
import com.tg.escrow.escrow.CreditSummaryView;
import com.tg.escrow.escrow.Leaderboard;
import com.tg.escrow.escrow.TraderCredit;
import com.tg.escrow.escrow.TraderCreditService;
import com.tg.escrow.escrow.TraderStats;
import com.tg.escrow.escrow.TraderStatsPort;
import com.tg.escrow.moderation.UserPreferencePort;

import java.util.List;

/**
 * 信用/榜单域命令处理器——从 {@link TradeCommandHandler} 拆出（2026-10-03 拆分时方法体逐字保留）。
 *
 * <p>覆盖子命令：{@code rank} / {@code credit} / {@code publish} / {@code unpublish}。
 * 评分、等级、排序与脱敏口径全部在域服务层（{@link TraderCreditService}/{@link Leaderboard}/
 * {@link CreditSummaryView}），本类只做参数解析与偏好读写。
 */
final class CreditCommands {

    private static final int DEFAULT_RANK_SIZE = 10;
    private static final int MAX_RANK_SIZE = 20;

    private final TraderStatsPort statsPort;
    private final TraderCreditService credit;
    /** ET-80：榜单公开偏好读写（默认脱敏）。 */
    private final UserPreferencePort prefs;

    CreditCommands(TraderStatsPort statsPort, TraderCreditService credit, UserPreferencePort prefs) {
        this.statsPort = statsPort;
        this.credit = credit;
        this.prefs = prefs;
    }

    /**
     * 交易榜（ET-62/77/78/79/80）。
     *
     * <h2>为什么默认脱敏、opt-in 才公开（ET-80）</h2>
     * <p>{@code Leaderboard} 的默认取向就是<b>脱敏</b>（"公开"是需要用户主动选择的例外）。
     * ET-80 落地后该事实由 {@link UserPreferencePort#rankVisibilityOf} 提供——<b>未登记 / 从未表态
     * 都是不公开</b>，公开者显示其 @username，其余仍显示脱敏 ID。
     *
     * <h2>为什么不做定时推送</h2>
     * <p>周榜/月榜的"周期"需要调度器，而"不引入调度器"是本项目的既定非目标。这里采用
     * <b>命令触发</b>的惰性口径：谁想看就发一次命令，看到的是"此刻"的累计榜。
     */
    String handleRank(BotCommand cmd) {
        int topN = DEFAULT_RANK_SIZE;
        String raw = cmd.argOpt(1).orElse(null);
        if (raw != null && !raw.isBlank()) {
            int parsed;
            try {
                parsed = Integer.parseInt(raw.trim());
            } catch (NumberFormatException ex) {
                return "榜单条数必须是数字，例如 /escrow rank 10。";
            }
            if (parsed < 1 || parsed > MAX_RANK_SIZE) {
                return "榜单条数必须在 1–" + MAX_RANK_SIZE + " 之间。";
            }
            topN = parsed;
        }

        List<TraderCredit> ranked = credit.rank(statsPort.allWithActivity(), topN);
        // ET-80：默认脱敏，仅对「主动公开且有 @username」者显示其用户名（@ 前缀随回执口径）。
        List<Leaderboard.Entry> entries = ranked.stream()
                .map(c -> {
                    UserPreferencePort.RankVisibility v = prefs.rankVisibilityOf(c.userId());
                    boolean open = v.publicProfile()
                            && v.username() != null && !v.username().isBlank();
                    String label = open ? "@" + v.username() : String.valueOf(c.userId());
                    return new Leaderboard.Entry(label, c.score(), c.tier(), open);
                })
                .toList();
        return Leaderboard.render(entries, topN, false);
    }

    /**
     * ET-80 榜单公开 opt-in / opt-out。
     *
     * <h2>为什么开启时强制要求 @username</h2>
     * <p>公开的语义是「展示用户名」。用户若从未设置 Telegram 用户名，系统没有可公开的名字——
     * 不能退回展示数字 ID（那既不是"用户名"，也与匿名榜无区别）。故明确告知先去设置。
     */
    String handlePublish(CommandActor actor, boolean open) {
        if (open) {
            if (actor.username() == null) {
                return "你还没有设置 Telegram 用户名，无法在榜单公开。"
                        + "请先在 Telegram「设置 → 用户名」里创建一个，再重新发送 /escrow publish。";
            }
            prefs.setRankPublic(actor.userId(), true, actor.username());
            return "已设置：此后榜单将显示你的用户名 @" + actor.username()
                    + "。如需隐藏，发送 /escrow unpublish。";
        }
        prefs.setRankPublic(actor.userId(), false, null);
        return "已设置：榜单将隐藏你的用户名（脱敏显示）。如需公开，发送 /escrow publish。";
    }

    /**
     * 个人信用摘要（ET-03/37/75/76 的用户可见出口）。
     *
     * <h2>为什么只查自己、且带了 ID 就拒绝</h2>
     * <p>榜单默认脱敏——公开输出里没有任何明文数字用户 ID。若这里能按 ID 查他人信用，
     * 那份脱敏就形同虚设。故本命令<b>刻意不提供</b>他人查询入口，且<b>不</b>退化成
     * "忽略参数、回自己的"——那会让人以为看到的是所填 ID 的信用，比明确拒绝更糟。
     */
    String handleCredit(BotCommand cmd, CommandActor actor) {
        String extra = cmd.argOpt(1).orElse(null);
        if (extra != null && !extra.isBlank()) {
            return TradeCommandHandler.CREDIT_SELF_ONLY;
        }
        TraderStats stats = statsPort.of(actor.userId());
        // 互刷感知（④ 差距 3）：对手统计走同一端口按需取——只有对手确实可疑时才会被查到
        TraderCreditService.CreditView view = credit.viewOf(stats, statsPort::of);
        return CreditSummaryView.render(view.credit(), view.breakdown(), view.excludedTrades());
    }
}
