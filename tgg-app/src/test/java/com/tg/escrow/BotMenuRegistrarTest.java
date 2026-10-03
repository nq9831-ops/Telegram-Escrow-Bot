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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeAllChatAdministrators;
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeDefault;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 命令菜单注册器的行为固定测试。
 *
 * <h2>为什么要它</h2>
 * <p>未注册菜单时，用户在输入框敲 {@code /} 看不到任何命令（Telegram 只显示 setMyCommands
 * 登记过的项）——"命令即产品"的 bot 却没有任何可发现性入口。本类钉住两条不变量：
 * <ol>
 *   <li><b>只注册 default scope</b>：用户命令（群管理已移除——管理员 scope 与 default 不再有差异，
 *       单独注册只会给 Telegram 送一份重复清单）；</li>
 *   <li><b>失败不阻断启动</b>：菜单是发现性增强，缺了 bot 完全可用；Telegram API 抖动
 *       不该让服务起不来（与 BotRunner 的 fail-fast 取向刻意相反，理由见实现类注释）。</li>
 * </ol>
 */
class BotMenuRegistrarTest {

    @Test
    @DisplayName("start：只注册 default scope（用户命令）一次，内容对应")
    void registersDefaultScope() throws Exception {
        TelegramClient client = mock(TelegramClient.class);
        BotMenuRegistrar registrar = new BotMenuRegistrar(client);
        registrar.start();

        ArgumentCaptor<SetMyCommands> captor = ArgumentCaptor.forClass(SetMyCommands.class);
        verify(client).execute(captor.capture());
        SetMyCommands call = captor.getValue();

        assertThat(call.getScope()).isInstanceOf(BotCommandScopeDefault.class);
        assertThat(call.getCommands())
                .extracting(org.telegram.telegrambots.meta.api.objects.commands.BotCommand::getCommand)
                .containsExactlyElementsOf(
                        BotMenu.userCommands().stream().map(BotMenu.Command::name).toList());

        assertThat(registrar.isRunning()).isTrue();
    }

    @Test
    @DisplayName("Telegram 不可达（execute 抛）：start 不抛、运行态标记完成——菜单失败不拖垮启动")
    void failureDoesNotBreakStartup() throws Exception {
        TelegramClient client = mock(TelegramClient.class);
        doThrow(new TelegramApiException("401 Unauthorized"))
                .when(client).execute(any(SetMyCommands.class));
        BotMenuRegistrar registrar = new BotMenuRegistrar(client);

        assertThatCode(registrar::start).doesNotThrowAnyException();
        assertThat(registrar.isRunning()).isTrue();
    }

    @Test
    @DisplayName("重复 start 幂等：第二次不再调用 Telegram（生命周期重入防护）")
    void repeatedStartIsIdempotent() throws Exception {
        TelegramClient client = mock(TelegramClient.class);
        BotMenuRegistrar registrar = new BotMenuRegistrar(client);
        registrar.start();
        registrar.start();

        verify(client, times(1)).execute(any(SetMyCommands.class));
    }

    @Test
    @DisplayName("构造：client 不可为空——缺它则注册静默不发生")
    void clientRequired() {
        assertThatThrownBy(() -> new BotMenuRegistrar(null))
                .isInstanceOf(com.tg.escrow.common.TggException.class)
                .hasMessageContaining("TelegramClient");
    }
}
