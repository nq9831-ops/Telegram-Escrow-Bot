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
import com.tg.escrow.core.GroupAdminPort;
import com.tg.escrow.core.MemberJoined;
import com.tg.escrow.core.ProtectionMode;
import com.tg.escrow.core.WelcomeTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 新成员入群处理——欢迎语渲染，以及保护模式的<b>实际拦截</b>。
 *
 * <h2>顺序：先判保护模式，再欢迎</h2>
 * <p>保护模式开启时<b>不发欢迎语</b>——向一个本该被拦下的人说"欢迎"是自相矛盾的信号，
 * 也让管理员看不出拦截是否生效。
 *
 * <h2>「拒绝」必须真的执行</h2>
 * <p>本类最初只返回一句"暂不接受新成员"的文案、<b>不执行任何动作</b>：于是提示在说人被拒了，
 * 人却还留在群里——一句做不到承诺的话。现接入 {@link GroupAdminPort#kick} 真正移出。
 * 踢人失败时回执会<b>如实说失败</b>（而不是照发"暂不接受"，那会让人以为人已被移出）。
 *
 * <h2>变量的诚实处理</h2>
 * <p>显示名/群名缺失时传空串而不是 {@code null}：{@link WelcomeTemplate#render} 对缺失变量
 * 是保守的（不泄漏 {@code {...}} 原文），但传 {@code null} 会变成"变量存在、值为空"，
 * 渲染出空洞的欢迎语。宁可显式传空串，让模板作者自行决定兜底文案。
 */
public final class MemberJoinHandler {

    private final ProtectionMode protection;
    private final WelcomeTemplate welcome;
    private final GroupAdminPort admin;

    public MemberJoinHandler(ProtectionMode protection, WelcomeTemplate welcome, GroupAdminPort admin) {
        if (protection == null || welcome == null) {
            throw new TggException("入群处理：保护模式与欢迎模板均不可为空");
        }
        if (admin == null) {
            throw new TggException("入群处理：管理动作端口不可为空"
                    + "（缺它则保护模式只能提示、无法真正拦截）");
        }
        this.protection = protection;
        this.welcome = welcome;
        this.admin = admin;
    }

    /**
     * 处理一次入群。
     *
     * @param joined 入群事件
     * @return 应发出的回执；{@code null} 语义不适用，恒为 present
     */
    public Optional<String> onMemberJoined(MemberJoined joined) {
        if (joined == null) {
            throw new TggException("入群处理：事件不可为空");
        }
        if (protection.shouldRejectJoin()) {
            if (!attemptKick(admin, joined.chatId(), joined.userId())) {
                return Optional.of("⚠️ 本群正处于保护模式，但移出新成员失败（操作未成功），"
                        + "请管理员手动处理。");
            }
            String reason = protection.reason();
            return Optional.of("⚠️ 本群正处于保护模式" + (reason == null || reason.isBlank()
                    ? "" : "（" + reason + "）") + "，暂不接受新成员。请管理员确认情况后关闭保护模式。");
        }
        Map<String, String> vars = new HashMap<>();
        vars.put("username", joined.userName() == null ? "" : joined.userName());
        vars.put("group", joined.chatTitle() == null ? "" : joined.chatTitle());
        return Optional.of(welcome.render(vars));
    }

    /**
     * 尝试移出成员。
     *
     * <p>失败<b>不抛</b>（调用方还要把回执发出去，异常逃逸会让提示一起丢掉），但必须留痕——
     * 静默失败会把"已拦截"变成谎报。返回值供调用方决定回执该怎么说。
     *
     * @return 操作成功为 {@code true}
     */
    static boolean attemptKick(GroupAdminPort admin, long chatId, long userId) {
        try {
            admin.kick(chatId, userId);
            return true;
        } catch (RuntimeException ex) {
            System.err.println("入群处理：移出成员失败（chat=" + chatId + " user=" + userId
                    + "）：" + ex.getMessage());
            return false;
        }
    }
}
