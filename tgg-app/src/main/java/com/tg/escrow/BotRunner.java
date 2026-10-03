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
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

/**
 * Bot 常驻接线（S1 的最后一环）：把 {@link TelegramBotHandler} 注册进长轮询并启动。
 *
 * <h2>为什么是独立的一环</h2>
 * <p>handler 只描述「一条 Update 怎么处理」，不会自己去连 Telegram；没有本类，
 * 上下文启动后进程<b>不会收任何消息</b>——bot 表现为静默无响应。
 *
 * <h2>生命周期</h2>
 * <p>实现 {@link SmartLifecycle} 且 {@code getPhase() = Integer.MAX_VALUE}：
 * 最后启动（等 DB/Web 就绪后才连 Telegram）、最先停止（先断外网再关资源）。
 * 注册失败（token 无效 / 网络不通）直接抛，让启动<b>失败可见</b>，不静默空转。
 */
@Component
public class BotRunner implements SmartLifecycle {

    private final BotTokenConfig tokenConfig;
    private final TelegramBotHandler handler;

    private TelegramBotsLongPollingApplication application;
    private volatile boolean running;

    public BotRunner(BotTokenConfig tokenConfig, TelegramBotHandler handler) {
        this.tokenConfig = tokenConfig;
        this.handler = handler;
    }

    @Override
    public void start() {
        if (running) {
            return;
        }
        try {
            TelegramBotsLongPollingApplication app = new TelegramBotsLongPollingApplication();
            app.registerBot(tokenConfig.token(), handler);
            // registerBot 是否自带启动随库版本而异，故显式兜底：未在运行就启动
            if (!app.isRunning()) {
                app.start();
            }
            this.application = app;
            this.running = true;
        } catch (TelegramApiException ex) {
            throw new TggException("Bot 启动失败：长轮询注册失败", ex);
        }
    }

    @Override
    public void stop() {
        TelegramBotsLongPollingApplication app = this.application;
        this.application = null;
        this.running = false;
        if (app != null) {
            try {
                app.close();
            } catch (Exception ex) {
                // 关闭失败不阻塞进程退出，但必须留痕——静默吞掉会让"卡住不退"难以归因
                System.err.println("Bot 停止时关闭长轮询应用失败：" + ex.getMessage());
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** 最后启动、最先停止。 */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }
}
