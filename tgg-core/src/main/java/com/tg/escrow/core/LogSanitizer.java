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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Pattern;

/**
 * 日志脱敏（原文档 G24）。
 *
 * <h2>两类敏感数据，两种处置方式——因为可识别性不同</h2>
 * <ul>
 *   <li><b>Bot Token</b>：格式明确（{@code <数字>:<长随机串>}），因此可以<b>自动扫描并替换</b>。
 *       最常泄露的位置是 URL——{@code api.telegram.org/bot<TOKEN>/getMe} 被原样打进日志，
 *       而那串 token 能让人完全接管机器人。</li>
 *   <li><b>用户 ID</b>：是一串普通数字，<b>无法从文本里自动识别</b>——日志里还有金额、
 *       订单号、时间戳、消息长度，全都长得一样。把它做成"自动掩码所有数字"会把日志变成
 *       无法阅读的噪声。所以这里只提供显式入口，由调用方在写入敏感字段时主动调用。</li>
 * </ul>
 *
 * <h2>用户 ID 用哈希而非"打星号"</h2>
 * <p>{@code 138****8000} 这类掩码看起来安全，但配合其他信息（区号、位数、出现时间）
 * 常常足以反推。这里改用 SHA-256 截断：<b>不可逆</b>，但同一个 ID 恒定映射到同一标识，
 * 因此仍能在日志里把同一用户的多条事件串起来——可关联，不可反查。
 *
 * <h2>本类的边界（重要）</h2>
 * <p>它只处理<b>已知格式</b>的凭据。下列情况它无能为力，需靠代码纪律而非工具：
 * <ul>
 *   <li>调用方把整个配置对象、请求体、异常堆栈原样打进日志；</li>
 *   <li>其他服务商的凭据（支付密钥、数据库密码）——格式不在本类知识范围内；</li>
 *   <li>已被写进磁盘的历史日志。脱敏是入口处的防线，不是事后清理。</li>
 * </ul>
 */
public final class LogSanitizer {

    /**
     * Telegram Bot Token 的形状：6 位以上数字，冒号，30 位以上的 {@code [A-Za-z0-9_-]}。
     *
     * <p>下限刻意取宽（真实 token 的数字段更长、随机段是 35 位），因为误判的代价不对称：
     * 漏判会让凭据进日志，误判只是把一小段无害文本变成 {@code ***}。
     * 但也不放宽到会吃掉 {@code 12:34:56} 这类时间戳——那会让日志失去可读性。
     */
    private static final Pattern TOKEN_PATTERN =
            Pattern.compile("\\d{6,}:[A-Za-z0-9_-]{30,}");

    /** 替换后的标记。不保留任何原字符，避免"部分泄露"。 */
    private static final String MASK = "***";

    /** 用户 ID 掩码的前缀，便于在日志中辨认这是脱敏后的标识。 */
    private static final String USER_PREFIX = "u:";

    /** 掩码保留的十六进制位数（24 bit）——旧无盐方法用；新代码用带盐方法（48 bit）。 */
    private static final int HASH_HEX_CHARS = 6;

    /** 带盐方法保留的十六进制位数（48 bit，碰撞空间 2^48）。 */
    private static final int SALT_HASH_HEX_CHARS = 12;

    private LogSanitizer() {
    }

    /**
     * 扫描并掩码文本中的全部 Bot Token。
     *
     * <p>{@code null} 原样返回 {@code null}——日志路径不该因为脱敏而抛异常，
     * 那会导致"为了安全把日志打崩"，最终结果是有人把脱敏整段注释掉。
     */
    public static String maskTokens(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return TOKEN_PATTERN.matcher(text).replaceAll(MASK);
    }

    /**
     * 把用户 ID 转成不可反查、但可关联的标识（无盐——<b>仅向后兼容</b>）。
     *
     * <p><b>预映像风险</b>：无盐哈希可被预计算字典反推，且 24 bit 空间在大用户量下会碰撞。
     * <b>新代码请使用 {@link #maskUserId(long, String)}</b>（带部署级盐 + 48 bit 输出）。
     *
     * <p>**调用方必须显式调用**——本类不提供"自动掩码所有数字"的入口，
     * 因为那会同时掩掉订单号和金额，使日志失去排查价值。
     */
    public static String maskUserId(long userId) {
        return USER_PREFIX + shortHash(Long.toString(userId), HASH_HEX_CHARS);
    }

    /**
     * 把用户 ID 转成不可反查、但可关联的标识（<b>带盐，推荐</b>，ET-57 预映像防护）。
     *
     * <p>盐来自部署级配置（环境变量注入）：不同部署产生不同映射，字典预计算跨部署失效；
     * 输出 48 bit（12 位 hex）把碰撞空间从 2²⁴ 提高到 2⁴⁸。同一盐内同一 ID 恒定映射，
     * 日志仍可把同一用户的事件串起来——可关联，不可反查。
     *
     * @param userId 用户 ID
     * @param salt   部署级盐值（<b>必填</b>——无盐调用等于没修，故 fail-closed）
     */
    public static String maskUserId(long userId, String salt) {
        if (salt == null || salt.isBlank()) {
            throw new TggException("用户 ID 脱敏：盐值必须提供（无盐哈希可被预计算反推）");
        }
        return USER_PREFIX + shortHash(salt + ":" + userId, SALT_HASH_HEX_CHARS);
    }

    private static String shortHash(String value, int hexChars) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hexChars);
            for (int i = 0; i < hexChars / 2; i++) {
                sb.append(Character.forDigit((bytes[i] >> 4) & 0xF, 16));
                sb.append(Character.forDigit(bytes[i] & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 是 JDK 必须提供的算法；走到这里说明运行环境异常，不能静默降级为明文
            throw new TggException("运行环境缺少 SHA-256——拒绝以降级方式输出用户 ID", ex);
        }
    }
}
