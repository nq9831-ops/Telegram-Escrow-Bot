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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScope;
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeDefault;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.List;

/**
 * 命令菜单注册器（Telegram {@code /} 菜单）：启动时把 {@link BotMenu} 的内容
 * 登记到 default scope（群管理移除后 all_chat_administrators 与 default 不再有差异）。
 *
 * <h2>失败取向：warn 不抛（与 BotRunner 相反，刻意的）</h2>
 * <p>{@code BotRunner} 的注册失败会让 bot <b>完全不可用</b>（收不到任何消息），故 fail-fast；
 * 而菜单只是<b>发现性增强</b>——注册失败时 bot 一切正常，只是 {@code /} 列表为空。
 * 把"Telegram 侧一次 API 抖动"升格为"服务起不来"，是用可用性换取的非必要严格。
 * 所以这里失败只留痕（不静默：warn 日志点名 scope 与原因），启动照常继续。
 *
 * <h2>生命周期</h2>
 * <p>与 {@code BotRunner} 同为 {@link SmartLifecycle}，phase 比它小 1：先亮出菜单、
 * 再开始收消息（停止顺序相反——菜单是服务器端状态，无需清理动作）。
 */
@Component
public final class BotMenuRegistrar implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(BotMenuRegistrar.class);

    private final TelegramClient client;
    private volatile boolean running;

    /**
     * @param client Telegram 客户端（与回复出口共用同一实例）
     */
    public BotMenuRegistrar(TelegramClient client) {
        if (client == null) {
            throw new TggException("命令菜单注册：TelegramClient 不可为空（缺它则注册静默不发生）");
        }
        this.client = client;
    }

    @Override
    public void start() {
        if (running) {
            return;
        }
        running = true;
        registerScope(new BotCommandScopeDefault(), BotMenu.userCommands(), "default");
    }

    /** 单个 scope 的注册；任何失败都止步于 warn（见类注释的取向说明）。 */
    private void registerScope(BotCommandScope scope, List<BotMenu.Command> commands,
                               String scopeName) {
        try {
            client.execute(SetMyCommands.builder()
                    .commands(commands.stream().map(BotMenuRegistrar::toTelegram).toList())
                    .scope(scope)
                    .build());
            log.info("命令菜单已注册（{} scope，{} 条）", scopeName, commands.size());
        } catch (TelegramApiException | RuntimeException ex) {
            log.warn("命令菜单注册失败（{} scope）：{}——菜单是发现性增强，不阻断启动",
                    scopeName, ex.getMessage());
        }
    }

    /** 纯数据 → telegrambots 类型（Telegram 依赖收口在本类与回复适配器）。 */
    private static org.telegram.telegrambots.meta.api.objects.commands.BotCommand toTelegram(
            BotMenu.Command command) {
        return org.telegram.telegrambots.meta.api.objects.commands.BotCommand.builder()
                .command(command.name())
                .description(command.description())
                .build();
    }

    @Override
    public void stop() {
        // 菜单是 Telegram 服务器端状态，停止时无需清理（与 BotRunner 关闭长轮询不同）；
        // 仅标记停止以防生命周期重入。
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** 在 BotRunner（{@code Integer.MAX_VALUE}）之前启动——先亮出菜单，再开始收消息。 */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 1;
    }
}
