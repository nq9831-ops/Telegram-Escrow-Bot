-- 持久化 V4：待接受邀请表 trade_invites。
--
-- 背景：深链邀请流「先建邀请、后绑卖方」——发起人创建邀请时卖方未知，故不能直接落
-- escrow_orders（其 seller_user_id 是 NOT NULL 的承重不变量）。把「待接受」这一独立阶段
-- 放进本表，接单成功那一刻才物化出一笔双方齐全的订单——escrow_orders 及其状态机零改动。
--
-- 【可移植性】遵循 V1__init_escrow.sql 文末约束：
--   1) 索引用独立 CREATE INDEX，不写进建表语句内联 KEY（H2 解析不了内联 KEY）；
--   2) 不写 ENGINE=InnoDB / DEFAULT CHARSET / COLLATE（MySQL 8 默认即 InnoDB + utf8mb4）。
-- 测试用 H2 的 MODE=MySQL 跑本迁移，只有可移植 DDL 才能让 ddl-auto=validate 真正生效。
--
-- token 唯一：接单入口按令牌定位，唯一索引既保证定位唯一，也让随机令牌的碰撞在写入期即失败。
-- accepted_order_id 为 NULL 表示尚未被接受；被接受后写入物化订单号（一次性）。
-- version 供乐观锁：并发抢接由版本冲突兜底（对应实体侧 @Version）。

CREATE TABLE trade_invites (
    id                BIGINT         NOT NULL AUTO_INCREMENT,
    version           BIGINT         NOT NULL DEFAULT 0,
    token             VARCHAR(64)    NOT NULL,
    buyer_user_id     BIGINT         NOT NULL,
    amount            DECIMAL(24, 8) NOT NULL,
    currency          VARCHAR(16)    NOT NULL,
    created_at        DATETIME(6)    NOT NULL,
    expires_at        DATETIME(6)    NOT NULL,
    accepted_order_id BIGINT         NULL,
    PRIMARY KEY (id)
);

CREATE UNIQUE INDEX idx_trade_invites_token ON trade_invites (token);
CREATE INDEX idx_trade_invites_buyer ON trade_invites (buyer_user_id);
