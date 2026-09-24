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

import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

/**
 * 刷屏检测（原文档 G7）：同一内容在窗口内重复达到阈值即判刷屏。
 *
 * <h2>为什么按"内容哈希"而不是"发送频率"</h2>
 * <p>无差别地按频率判定会误伤正常讨论——正常人也会连发两条。真正需要拦的是
 * <b>相同内容反复刷</b>（广告、复读）。所以计数以归一化后的内容为键，不同内容各自计数。
 *
 * <h2>归一化的边界</h2>
 * <p>大小写、首尾空白、连续空白的差异在人类看来是"同一条"，所以先做 NFC 归一化 +
 * 折叠空白 + {@link Locale#ROOT} 小写折叠，再比对。用 {@code ROOT} 而非默认 locale：
 * 否则判定结果会随部署机器的语言环境漂移。
 *
 * <h2>阈值语义：达到即算</h2>
 * <p>{@code count >= threshold}。配置写 3 的人期望的是"发到第 3 条就拦"。
 *
 * <h2>内存回收</h2>
 * <p>以内容为 key 意味着 key 数量随「出现过的不同内容」增长。若只清理被再次访问的 key，
 * 一次性内容的 key 会永久驻留——常驻 Bot 会因此缓涨至 OOM。故每隔一个窗口做一次全表清扫，
 * 回收已滑出窗口的 key（见 {@link #sweepIfDue}）。
 *
 * <p>本类<b>不持有外部时钟</b>——每次判定都要求调用方传入 {@code now}，
 * 这使窗口行为可测且同一判定内时刻一致。线程安全未做保证：调用方需串行化同一 key 的访问
 * （Bot 的消息处理是单线程派发，符合此前提）。
 */
public final class MessageRepeatDetector {

    private final Duration window;
    private final int threshold;
    private final Map<String, Deque<Instant>> history = new HashMap<>();

    /** 上次全表清扫的时刻。惰性清扫避免"每个 key 永久驻留"。 */
    private Instant lastSweep;

    /**
     * @param window    计数窗口（正数）
     * @param threshold 达到即判刷屏的重复次数（≥ 2）
     */
    public MessageRepeatDetector(Duration window, int threshold) {
        if (window == null) {
            throw new TggException("刷屏检测：未提供窗口");
        }
        if (window.isZero() || window.isNegative()) {
            throw new TggException("刷屏检测：窗口必须为正，实为 " + window);
        }
        if (threshold < 2) {
            throw new TggException("刷屏检测：阈值必须 ≥ 2，实为 " + threshold
                    + "——阈值 1 会让每条消息都被判刷屏");
        }
        this.window = window;
        this.threshold = threshold;
    }

    /**
     * 记录一条消息并判定是否刷屏。
     *
     * <p>空内容、{@code null}、纯空白<b>不计入</b>：非文本消息是常态，不该累加成刷屏。
     *
     * @param content 消息文本
     * @param now     当前时刻
     * @return 归一化后同一内容在窗口内的计数是否已达到阈值
     */
    public boolean isSpam(String content, Instant now) {
        if (now == null) {
            throw new TggException("刷屏检测：未提供当前时刻");
        }
        String key = normalize(content);
        if (key == null) {
            return false;
        }

        Deque<Instant> stamps = history.computeIfAbsent(key, k -> new ArrayDeque<>());
        Instant cutoff = now.minus(window);
        while (!stamps.isEmpty() && stamps.peekFirst().isBefore(cutoff)) {
            stamps.pollFirst();
        }
        stamps.addLast(now);
        sweepIfDue(now);

        return stamps.size() >= threshold;
    }

    /**
     * 当前跟踪的内容 key 数量——供监控与测试观察内部状态规模。
     *
     * <p>无清扫时它会随「出现过的不同内容数」无界增长（常驻进程的内存泄漏）。
     */
    public int trackedContentCount() {
        return history.size();
    }

    /**
     * 到期则做一次全表清扫。
     *
     * <p>为什么需要它：{@link #isSpam} 只清理<b>被再次访问</b>的那个 key。一次性内容
     * （再也不重复的消息）的 key 会永远留在表里——群里内容千变万化，这就是无界增长。
     * 每隔一个窗口做一次全表扫描，把已滑出窗口的 key 回收掉。
     */
    private void sweepIfDue(Instant now) {
        if (lastSweep == null || !now.isBefore(lastSweep.plus(window))) {
            sweep(now);
            lastSweep = now;
        }
    }

    /** 清掉所有「窗口内已无时间戳」的 key。 */
    private void sweep(Instant now) {
        Instant cutoff = now.minus(window);
        Iterator<Map.Entry<String, Deque<Instant>>> it = history.entrySet().iterator();
        while (it.hasNext()) {
            Deque<Instant> stamps = it.next().getValue();
            while (!stamps.isEmpty() && stamps.peekFirst().isBefore(cutoff)) {
                stamps.pollFirst();
            }
            if (stamps.isEmpty()) {
                it.remove();
            }
        }
    }

    /** 归一化：trim → 折叠连续空白 → NFC → 小写折叠。空结果返回 {@code null}。 */
    private static String normalize(String content) {
        if (content == null) {
            return null;
        }
        String s = content.trim();
        if (s.isEmpty()) {
            return null;
        }
        s = Normalizer.normalize(s, Normalizer.Form.NFC);
        s = s.replaceAll("\\s+", " ");
        return s.toLowerCase(Locale.ROOT);
    }
}
