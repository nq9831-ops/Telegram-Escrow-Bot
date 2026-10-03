-- 持久化 V6：交易群绑定表 trade_groups（Wave 1 接线 TradeGroupLifecycle / TradeGroupService）。
--
-- 背景：「交易群与交易 ID 绑定；每笔新交易新建群，不复用」（docs/requirements/SPEC.md:327，
-- 消解业务冲突 1.2）。TradeGroupLifecycle 是内存状态机，重启即丢——本表把它的状态与
-- 两个关键时刻落库，由 TradeGroupService 每次「从存储恢复状态机再推进」。
--
-- 主键即交易号：天然保证「一交易一群」。chat_id 落唯一索引（可空，为建群前中间态留位），
-- 既保证一群不绑两笔交易，也为后续 chatId → tradeId 反查预留（反查命令本波不做，数据先落）。
--
-- 群是交易的辅助载体，故本表刻意不设指向 escrow_orders 的外键：建群/退群属可降级的旁路，
-- 不该因它牵动订单表的约束与写入。
--
-- 【可移植性】遵循 V1__init_escrow.sql 文末约束：
--   1) 索引用独立 CREATE INDEX，不写进建表语句内联 KEY（H2 解析不了内联 KEY）；
--   2) 不写 ENGINE=InnoDB / DEFAULT CHARSET / COLLATE（MySQL 8 默认即 InnoDB + utf8mb4）。
-- 测试用 H2 的 MODE=MySQL 跑本迁移；只有可移植 DDL 才能让 ddl-auto=validate 真正生效。

CREATE TABLE trade_groups (
    trade_id           BIGINT      NOT NULL,
    chat_id            BIGINT      NULL,
    state              VARCHAR(16) NOT NULL,
    silence_started_at DATETIME(6) NULL,
    archived_at        DATETIME(6) NULL,
    updated_at         DATETIME(6) NOT NULL,
    PRIMARY KEY (trade_id)
);

-- 唯一索引保证「一个群只绑一笔交易」；chat_id 可空时 H2/MySQL 均允许多个 NULL（建群前中间态）。
CREATE UNIQUE INDEX idx_trade_groups_chat ON trade_groups (chat_id);
