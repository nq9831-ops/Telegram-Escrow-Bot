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

import com.tg.escrow.common.TggException;

import java.util.Map;
import java.util.Optional;

/**
 * 按钮回调的编解码：{@code callback_data}（如 {@code rl:3}）↔ 等价命令文本（{@code /escrow release 3}）。
 *
 * <h2>为什么解码成"命令文本"而不是新动作模型</h2>
 * <p>按钮必须与命令<b>严格等价</b>——否则同一动作会有两条语义路径，权限守卫、状态守卫、
 * 文案披露都可能在其中一条上漂移。解码成一条普通命令文本后，就能原样复用既有分发与守卫链
 * （{@code TelegramBotHandler} → {@code BotDispatcher} → {@code TradeCommandHandler}）。
 *
 * <h2>两个方向刻意不对称</h2>
 * <ul>
 *   <li>{@link #encode} 是<b>内部</b>调用（按钮由我们自己造）——参数非法即 bug，故 fail-fast；</li>
 *   <li>{@link #decode} 吃的是<b>外部</b>数据（旧消息里的按钮可能属于已改版的编码，也可能被伪造），
 *       畸形输入返回 {@link Optional#empty()} 而非抛——它绝不能把更新处理线程打崩。</li>
 * </ul>
 *
 * <h2>编码形态（两种）</h2>
 * <ul>
 *   <li>{@code <verb>:<orderId>}——普通动作（如 {@code rl:3} → {@code /escrow release 3}）；</li>
 *   <li>{@code <verb>:<orderId>:y}——<b>二次确认</b>形态（如 {@code rl:3:y} →
 *       {@code /escrow release 3 --yes}）。三段里只有末段逐字 {@code y} 合法，其余一律不识别
 *       （畸形输入不算确认、也不抛——见下方不对称说明）。</li>
 * </ul>
 *
 * <p>两种形态都远低于 Telegram 的 64 字节上限。
 */
public final class ActionCodec {

    /** 动作缩写 → 子命令名（与 {@code TradeCommandHandler} 的 {@code SUB_*} 一一对应）。 */
    private static final Map<String, String> SUBCOMMANDS = Map.of(
            "st", "status",
            "lk", "lock",
            "dl", "deliver",
            "rl", "release",
            "rf", "refund",
            "dp", "dispute");

    private static final char SEPARATOR = ':';
    private static final String COMMAND = "escrow";
    /** 二次确认三段形态的标记段——只有逐字 {@code y} 合法。 */
    private static final String YES_MARK = "y";
    /** 解码确认形态时追加到等价命令尾部的确认尾缀（与命令层逐字一致）。 */
    private static final String YES_SUFFIX = " --yes";

    private ActionCodec() {
    }

    /**
     * 编码一个普通动作按钮的 callback 数据（两段形态 {@code <verb>:<orderId>}）。
     *
     * @param verb    动作缩写（{@code st/lk/dl/rl/rf/dp}）
     * @param orderId 订单号（必须为正）
     * @throws TggException verb 未知或订单号非正——调用方是我们自己，错即 bug
     */
    public static String encode(String verb, long orderId) {
        return encode(verb, orderId, false);
    }

    /**
     * 编码一个动作按钮的 callback 数据（可选二次确认三段形态 {@code <verb>:<orderId>:y}）。
     *
     * @param verb      动作缩写（{@code st/lk/dl/rl/rf/dp}）
     * @param orderId   订单号（必须为正）
     * @param confirmed {@code true} = 确认形态，解码后等价命令带 {@code --yes} 尾缀
     * @throws TggException verb 未知或订单号非正——调用方是我们自己，错即 bug
     */
    public static String encode(String verb, long orderId, boolean confirmed) {
        if (verb == null || !SUBCOMMANDS.containsKey(verb)) {
            throw new TggException("动作编码：未知动作缩写（" + verb + "）");
        }
        if (orderId <= 0) {
            throw new TggException("动作编码：订单号必须为正，实为 " + orderId);
        }
        return confirmed
                ? verb + SEPARATOR + orderId + SEPARATOR + YES_MARK
                : verb + SEPARATOR + orderId;
    }

    /**
     * 解码 callback 数据为等价命令文本（两段普通形态与三段确认形态都吃）。
     *
     * @param callbackData 外部传入的按钮数据（可能为 null、空、或任意畸形串）
     * @return 等价命令文本（如 {@code /escrow release 3}；确认形态为
     *         {@code /escrow release 3 --yes}）；无法识别时为 {@link Optional#empty()}——<b>不抛异常</b>
     */
    public static Optional<String> decode(String callbackData) {
        if (callbackData == null || callbackData.isBlank()) {
            return Optional.empty();
        }
        int separator = callbackData.indexOf(SEPARATOR);
        // 分隔符必须在中间：既不能没有（"rl"），也不能在首位（":3"）
        if (separator <= 0) {
            return Optional.empty();
        }
        String subcommand = SUBCOMMANDS.get(callbackData.substring(0, separator));
        if (subcommand == null) {
            return Optional.empty();
        }
        String rest = callbackData.substring(separator + 1);
        boolean confirmed = false;
        int secondSeparator = rest.indexOf(SEPARATOR);
        if (secondSeparator >= 0) {
            // 三段形态：末尾必须是逐字 "y"，其余（空段 "rl:3:"、多余段 "rl:3:y:z"、错标 "rl:3:9"）
            // 一律不识别——确认标记被放宽成"任意尾巴"会让畸形按钮绕过确认环。
            if (!YES_MARK.equals(rest.substring(secondSeparator + 1))) {
                return Optional.empty();
            }
            confirmed = true;
            rest = rest.substring(0, secondSeparator);
        }
        // 订单号段不可为空（覆盖 "rl:" 这种分隔符落在末位的形态）
        if (rest.isEmpty()) {
            return Optional.empty();
        }
        long orderId;
        try {
            orderId = Long.parseLong(rest);
        } catch (NumberFormatException ex) {
            // 含非数字（"rl:abc" / "rl:y"）、超长数字都落这里
            return Optional.empty();
        }
        if (orderId <= 0) {
            return Optional.empty();
        }
        return Optional.of("/" + COMMAND + " " + subcommand + " " + orderId
                + (confirmed ? YES_SUFFIX : ""));
    }
}
