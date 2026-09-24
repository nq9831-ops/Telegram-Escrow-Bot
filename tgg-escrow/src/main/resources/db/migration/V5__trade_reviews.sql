-- 持久化 V5：交易评价表 trade_reviews（Wave 3 接线 TradeReview）。
--
-- 背景：TradeReview 是「有状态」的守卫——它靠内部 Set 记住谁评过，从而保证"双方各评一次"。
-- 若只留在内存，进程重启即丢这一事实，同一方可重复刷评价。故把"谁评过"落库。
--
-- 唯一索引 (order_id, reviewer_id)：让「同一方对同一订单只能评一次」在**数据库层**也成立，
-- 不只依赖应用层的内存 Set——两条防线任一失效都不会产生脏数据。
-- 该唯一索引同时服务「按订单查全部评价」，故不再另建 order_id 单列索引。
--
-- 【可移植性】遵循 V1__init_escrow.sql 文末约束：
--   1) 索引用独立 CREATE INDEX，不写进建表语句内联 KEY（H2 解析不了内联 KEY）；
--   2) 不写 ENGINE=InnoDB / DEFAULT CHARSET / COLLATE（MySQL 8 默认即 InnoDB + utf8mb4）。
-- 测试用 H2 的 MODE=MySQL 跑本迁移；只有可移植 DDL 才能让 ddl-auto=validate 真正生效。

CREATE TABLE trade_reviews (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    order_id    BIGINT      NOT NULL,
    reviewer_id BIGINT      NOT NULL,
    score       INT         NOT NULL,
    created_at  DATETIME(6) NOT NULL,
    PRIMARY KEY (id)
);

CREATE UNIQUE INDEX idx_trade_reviews_order_reviewer ON trade_reviews (order_id, reviewer_id);
