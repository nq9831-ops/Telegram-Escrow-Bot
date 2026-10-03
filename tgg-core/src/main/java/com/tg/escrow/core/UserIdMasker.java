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

import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * 用户 ID 的日志脱敏器——把「用什么盐脱敏」从各调用点收口成一处可注入的依赖。
 *
 * <h2>为什么需要它</h2>
 * <p>{@link LogSanitizer#maskUserId(long)} 是无盐、24 bit 的旧版，其自身文档已标注
 * 「仅向后兼容，<b>新代码请使用带盐版</b>」；而带盐版要求调用方手里有盐，盐又是部署级配置。
 * 若让各调用点各自去读环境变量，迟早出现「有的地方读了、有的地方没读」——
 * 这正是脱敏最容易静默失效的形态。收口成一个注入点后，装配层决定盐，调用点只负责调用。
 *
 * <h2>盐缺失或过弱时：随机盐，而不是照用</h2>
 * <p>部署者未配置盐、或配的盐短于 {@link #MIN_SALT_LENGTH} 时，本类<b>不</b>退回无盐 24 bit
 * （那是「可预计算字典反推」的形态），也<b>不</b>照用弱盐（48 bit 输出宽度并不能补偿低熵盐——
 * 抗字典预计算的假设本身就依赖盐够长），而是改用<b>进程级随机盐</b>：同一进程内仍可把同一用户的
 * 事件串起来，且无法被预计算。代价是重启后映射会变，故 {@link #usingEphemeralSalt()} 为真，
 * 装配层据此留一条提示。
 *
 * <h2>为什么不在缺盐时抛异常</h2>
 * <p>调用点在<b>日志路径</b>上。这里 fail-closed 地抛，等于「为了脱敏把日志打崩」，
 * 最终结果往往是有人把脱敏整段注释掉——那比强度降级更糟。取向与
 * {@link LogSanitizer#maskTokens(String)} 对 {@code null} 的处理一致：脱敏本身不该抛。
 */
public final class UserIdMasker {

    /**
     * 盐的最低长度。短于此视为弱盐——48 bit 的输出宽度并不能补偿低熵的盐，
     * 抗「预计算字典反推」的论证本身就建立在盐足够长之上。
     */
    public static final int MIN_SALT_LENGTH = 16;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final String salt;
    private final boolean ephemeral;

    /**
     * @param salt 部署级盐；{@code null}/空白/短于 {@link #MIN_SALT_LENGTH} 时改用进程级随机盐
     */
    public UserIdMasker(String salt) {
        if (salt == null || salt.isBlank() || salt.trim().length() < MIN_SALT_LENGTH) {
            this.salt = randomSalt();
            this.ephemeral = true;
        } else {
            this.salt = salt;
            this.ephemeral = false;
        }
    }

    /**
     * 把用户 ID 转成不可反查、但同一盐内可关联的日志标识。
     *
     * @param userId 用户 ID
     * @return 带 {@code u:} 前缀的 48 bit 十六进制标识（盐恒非空，不会抛）
     */
    public String mask(long userId) {
        return LogSanitizer.maskUserId(userId, salt);
    }

    /**
     * 把文本中出现的已知 ID 替换为其掩码——用于<b>第三方异常消息里内嵌的标识符</b>。
     *
     * <p>为什么必须有它：只把日志行的参数掩码掉是不够的。上层适配器包装异常时会把 ID
     * 写进消息本身（实测形态如 {@code 回复出口：发送文本失败（chat=2002）}），
     * 日志若原样打印 {@code ex.getMessage()}，同一行里刚掩掉的 ID 就被它原样抵消了。
     *
     * @param text 待清洗文本（{@code null} 原样返回）
     * @param ids  已知的标识符（调用点一定知道自己传了哪些 ID，无需猜测）
     * @return 清洗后的文本
     */
    public String scrub(String text, long... ids) {
        if (text == null || text.isEmpty() || ids == null) {
            return text;
        }
        String scrubbed = text;
        for (long id : ids) {
            scrubbed = scrubbed.replace(Long.toString(id), mask(id));
        }
        return scrubbed;
    }

    /** 是否正在使用进程级随机盐——即部署者<b>未</b>提供合用的固定盐。 */
    public boolean usingEphemeralSalt() {
        return ephemeral;
    }

    private static String randomSalt() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
