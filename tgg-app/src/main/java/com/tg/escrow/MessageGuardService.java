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
import com.tg.escrow.core.IncomingMessage;
import com.tg.escrow.core.MessageGuardOrchestrator;
import com.tg.escrow.moderation.WarningPort;

import java.util.Optional;

/**
 * 内容安全处置（Wave 3）——把 {@link MessageGuardOrchestrator} 的<b>判定</b>变成真实动作。
 *
 * <h2>为什么判定与处置分开</h2>
 * <p>判定（链接/媒体/限流是否命中）可以纯本地穷举测试；处置要碰 Telegram API，只能在一个地方发生。
 * 分开后，判定逻辑的测试不会因为网络而失真，处置路径也只有这一处需要盯。
 *
 * <h2>自动处置不冒充管理员</h2>
 * <p>规则触发时<b>不</b>去调 {@code WarningOrchestrator} 的判罚路径（那条路要求 actor ≥ ADMIN）——
 * 合成一个假管理员会让审计留下不存在的执行者。这里只做两件确定的事：<b>删消息</b> +
 * <b>累计警告数</b>（为后续阈值判罚积累），阈值判罚仍由管理员命令或独立调度触发。
 *
 * <h2>失败不静默</h2>
 * <p>删消息失败（网络/权限）一律抛 {@link TggException}，由调用方转成可见回执。
 * 悄悄吞掉会让管理员以为消息已被清理。
 */
public final class MessageGuardService {

    private final MessageGuardOrchestrator guard;
    private final GroupAdminPort admin;
    private final WarningPort warnings;

    public MessageGuardService(MessageGuardOrchestrator guard, GroupAdminPort admin, WarningPort warnings) {
        if (guard == null || admin == null || warnings == null) {
            throw new TggException("内容安全处置：判定器/管理动作端口/警告端口均不可为空");
        }
        this.guard = guard;
        this.admin = admin;
        this.warnings = warnings;
    }

    /**
     * 判定并处置。
     *
     * @param message 入站消息
     * @return 命中并已处置时返回面向用户的说明；放行时返回 {@link Optional#empty()}
     * @throws TggException 删除消息失败——绝不静默
     */
    public Optional<String> screen(IncomingMessage message) {
        if (message == null) {
            throw new TggException("内容安全处置：消息不可为空");
        }
        MessageGuardOrchestrator.Decision decision = guard.inspect(message);
        if (!decision.blocked()) {
            return Optional.empty();
        }
        // 先删（可能抛），再计数——删不掉就不该假装处置过
        admin.deleteMessage(message.chatId(), message.messageId());
        warnings.warn(message.chatId(), message.userId());
        return Optional.of("⚠️ 消息已被删除：" + decision.reason() + "（已记一次警告）");
    }
}
