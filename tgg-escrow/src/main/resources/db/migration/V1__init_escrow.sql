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
    PRIMARY KEY (id),
    -- 「同一买方同时最多 N 笔进行中交易」这一业务约束需要按买方+状态检索
    KEY idx_escrow_orders_buyer_state (buyer_user_id, state),
    -- 卖方侧同理由此索引支撑
    KEY idx_escrow_orders_seller_state (seller_user_id, state)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci;
