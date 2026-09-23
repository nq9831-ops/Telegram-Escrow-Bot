package com.tg.escrow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 应用入口。
 *
 * <p>当前阶段（担保交易核心）只包含领域对象与规则类，尚无 Web / Bot 装配——
 * 因此单元测试刻意<b>不</b>启动 Spring 上下文（纯 POJO 测试，不依赖数据库与 Telegram）。
 * 上下文启动在接入 Bot 与持久化后再补集成测试。
 */
@SpringBootApplication
public class EscrowBotApplication {

    public static void main(String[] args) {
        SpringApplication.run(EscrowBotApplication.class, args);
    }
}
