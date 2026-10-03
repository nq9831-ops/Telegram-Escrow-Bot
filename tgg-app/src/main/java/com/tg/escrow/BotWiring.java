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

import com.tg.escrow.core.MemberRolePort;
import com.tg.escrow.core.UserIdMasker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.Clock;

/**
 * Bot 核心装配——身份/时钟/客户端/回复出口/角色查询/分发器/入口处理器（8 bean）。
 * 其余 bean 按领域分在 {@link TradeWiring} / {@link ChainWiring}（原 {@code GuildWiring} 已随群管理移除）
 * （2026-10-03 拆分，方法体逐字保留，拆分时 bean 总数 63 不变；同日 GM-20 审计出口 +1、
 * GM-21 群标题缓存 +1、费用台账（ET-29/30）+1、TON Pay 引用（ET-31/32）+1、
 * 链上对账（ET-19）+1 → 68）；共享解析辅助在 {@link WiringSupport}。
 * Bot Token 只经环境变量（{@link BotTokenConfig#from}），绝不写入仓库。
 */
@Configuration
public class BotWiring {

    private static final Logger log = LoggerFactory.getLogger(BotWiring.class);

    /**
     * 日志脱敏器：盐取自 {@code tgg.log.salt}（部署者配置）。
     *
     * <p>未配置时用<b>进程级随机盐</b>并留一条提示——不退化到无盐 24 bit：那个形态可被字典
     * 预计算反推，其自身文档已明确劝退新代码。缺盐不该让日志打不出来，但也不该悄悄降级。
     */
    @Bean
    public UserIdMasker userIdMasker(@Value("${tgg.log.salt:}") String salt) {
        UserIdMasker masker = new UserIdMasker(salt);
        if (masker.usingEphemeralSalt()) {
            log.warn("tgg.log.salt（TGG_LOG_SALT）未配置或过弱（要求至少 {} 字符）："
                            + "用户 ID 脱敏改用进程级随机盐——同一用户在重启后的日志标识会变化；"
                            + "生产环境建议配置足够长的固定盐。",
                    UserIdMasker.MIN_SALT_LENGTH);
        }
        return masker;
    }

    /** 时钟：UTC，与 application.yml 的 hibernate time_zone 一致。 */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /** token 只从环境变量读（缺失即启动失败——不静默裸奔）。 */
    @Bean
    public BotTokenConfig botTokenConfig() {
        return BotTokenConfig.from(System::getenv);
    }

    /**
     * Telegram 客户端——<b>回复出口与角色查询共用同一个实例</b>（各自 new 一份会开两套连接池）。
     */
    @Bean
    public TelegramClient telegramClient(BotTokenConfig tokenConfig) {
        return new OkHttpTelegramClient(tokenConfig.token());
    }

    /** 回复出口：Telegram 依赖的唯一落点。 */
    @Bean
    public BotReplyPort botReplyPort(TelegramClient client) {
        return new TelegramBotReplyAdapter(client);
    }

    /** 群内角色查询（Wave 1）：替代此前 Bot 接线里硬编码的 MEMBER。 */
    @Bean
    public MemberRolePort memberRolePort(TelegramClient client) {
        return new TelegramMemberRoleAdapter(client);
    }

    @Bean
    public BotDispatcher botDispatcher(TradeCommandHandler tradeHandler,
                                       @Value("${tgg.bot.username}") String botUsername) {
        return new BotDispatcher(tradeHandler, botUsername);
    }

    @Bean
    public TelegramBotHandler telegramBotHandler(BotTokenConfig tokenConfig,
                                                 BotDispatcher dispatcher,
                                                 BotReplyPort reply,
                                                 @Value("${tgg.bot.username}") String botUsername,
                                                 @Value("${tgg.webapp.url:}") String webAppUrl,
                                                 MemberRolePort memberRolePort,
                                                 WeeklyBoardPush weeklyBoardPush) {
        return new TelegramBotHandler(tokenConfig, dispatcher, reply, botUsername, webAppUrl,
                memberRolePort, weeklyBoardPush);
    }
}
