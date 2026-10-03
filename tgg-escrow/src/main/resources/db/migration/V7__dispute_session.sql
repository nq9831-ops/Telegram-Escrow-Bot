-- 持久化 V7：争议会话表 escrow_disputes（Wave 1 接线 ET-60 双方陈述 + 承载 ET-45 证据窗口）。
--
-- 背景：争议此前只有「发起争议 + 写进订单 reason 的一句理由」——双方没有结构化的陈述入口，
-- 裁决方（联邦）看到的只是发起方那句话。DisputeStatementFlow（ET-60 双方陈述，含"对方确认已读"）
-- 与 EvidenceDeadline（ET-45 证据窗口）两个件早已建成却零调用方；本表承接它们的持久化，
-- 让"重启即忘"不成立（本仓既有教训：内存态必须落库，否则重启后状态全丢）。
--
-- 一订单一会话：主键即订单号，天然保证「一订单一会话」，且免去反查。
--
-- 陈述与证据窗口并存于本行：证据窗口不随维护期暂停（业务冲突 2.3 的解法），
-- 故窗口起点 opened_at 与会话同生，不设暂停字段——这一点由 EvidenceDeadline 的结构固化。
--
-- 刻意不设指向 escrow_orders 的外键：会话是争议的辅助记录，与订单账本职责不同
-- （同 trade_groups 的取向），不因它有订单表的约束牵连。
--
-- 【可移植性】遵循 V1__init_escrow.sql 文末约束：
--   1) 索引用独立 CREATE INDEX，不写进建表语句内联 KEY（H2 解析不了内联 KEY）；
--   2) 不写 ENGINE=InnoDB / DEFAULT CHARSET / COLLATE（MySQL 8 默认即 InnoDB + utf8mb4）。
-- 测试用 H2 的 MODE=MySQL 跑本迁移；只有可移植 DDL 才能让 ddl-auto=validate 真正生效。

CREATE TABLE escrow_disputes (
    order_id             BIGINT        NOT NULL,
    opened_at            DATETIME(6)   NOT NULL,
    evidence_deadline_at DATETIME(6)   NOT NULL,
    buyer_statement      VARCHAR(2000) NULL,
    seller_statement     VARCHAR(2000) NULL,
    buyer_read_at        DATETIME(6)   NULL,
    seller_read_at       DATETIME(6)   NULL,
    updated_at           DATETIME(6)   NOT NULL,
    PRIMARY KEY (order_id)
);
