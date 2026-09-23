-- 担保交易订单账本。
--
-- 结构与 EscrowOrder 的 JPA 映射一一对应，ddl-auto=validate 会在启动期校验二者一致：
-- 结构漂移在启动期就失败，而不是运行到某条 SQL 才炸。
--
-- 金额用 DECIMAL(24,8) 而非浮点：这是资金字段，二进制浮点会在比对与求差时引入
-- 不可解释的尾差。
--
-- state 用 VARCHAR(16) 而非 MySQL ENUM：ENUM 的合法值被固化在表定义里，
-- 增删状态要改表（DDL 锁表），而状态机的演进是常态。合法性由应用层的
-- fail-closed 守卫负责（未知值抛 EscrowException 而非放行）。
--
-- 【可移植性】刻意不用 MySQL 方言私有语法，原因见文末。

CREATE TABLE escrow_orders (
    id             BIGINT         NOT NULL AUTO_INCREMENT,
    buyer_user_id  BIGINT         NOT NULL,
    seller_user_id BIGINT         NOT NULL,
    amount         DECIMAL(24, 8) NOT NULL,
    currency       VARCHAR(16)    NOT NULL,
    state          VARCHAR(16)    NOT NULL,
    reason         VARCHAR(512)   NULL,
    created_at     DATETIME(6)    NOT NULL,
    updated_at     DATETIME(6)    NOT NULL,
    PRIMARY KEY (id)
);

-- 「同一买方同时最多 N 笔进行中交易」这一业务约束需要按买方+状态检索；
-- 卖方侧同理由第二个索引支撑。缺这两个索引时是全表扫描，买家多了会拖垮准入判定。
CREATE INDEX idx_escrow_orders_buyer_state ON escrow_orders (buyer_user_id, state);
CREATE INDEX idx_escrow_orders_seller_state ON escrow_orders (seller_user_id, state);

-- ── 为什么不用 MySQL 方言 ────────────────────────────────────────────
-- 1) 索引必须用独立的 CREATE INDEX，不能写进建表语句内联的 KEY idx_... (...)
--    ——H2 解析不了内联 KEY（SQL State 42001: expected "identifier"），
--    结果是「测试根本跑不起来」，而不是「测试过、生产炸」。
-- 2) 不写 ENGINE=InnoDB / DEFAULT CHARSET / COLLATE——MySQL 8.0 默认就是
--    InnoDB + utf8mb4，写上只让 H2 多一处解析失败点。
-- 测试用 H2 的 MODE=MySQL 跑这套迁移；只有可移植 DDL 才能让「迁移 ↔ 实体一致」
-- 的校验（ddl-auto=validate）真正生效。
