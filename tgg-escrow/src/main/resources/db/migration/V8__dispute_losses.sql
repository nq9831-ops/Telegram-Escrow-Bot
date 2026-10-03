-- 持久化 V8：争议败诉台账 dispute_losses（Wave 3 接线 ET-61「多维度惩罚」的唯一数据源）。
--
-- 背景：PenaltyPlanner 的 disputeLosses 入参此前**无数据源**——裁决只改订单状态，不记「谁败了」，
-- 于是「败诉 ≥ 2 → 投票暂停」这一维永远算不出来（ET-61 长期零引用）。
--
-- 败诉方口径（用户 2026-10-02 拍板）：裁决 outcome 对其不利的一方即败诉方——
--   RELEASE（放款卖方）→ 买方败；REFUND（退款买方）→ 卖方败。
--
-- 主键即订单号：一单一败方，天然幂等（重复裁决不会重复记罚）。
-- loser_user_id 落独立索引——计数按人查（`countByLoserUserId`），是全表扫描与否的分水岭。
--
-- 【可移植性】遵循 V1__init_escrow.sql 文末约束：
--   1) 索引用独立 CREATE INDEX，不写进建表语句内联 KEY（H2 解析不了内联 KEY）；
--   2) 不写 ENGINE=InnoDB / DEFAULT CHARSET / COLLATE（MySQL 8 默认即 InnoDB + utf8mb4）。
-- 测试用 H2 的 MODE=MySQL 跑本迁移；只有可移植 DDL 才能让 ddl-auto=validate 真正生效。

CREATE TABLE dispute_losses (
    order_id      BIGINT      NOT NULL,
    loser_user_id BIGINT      NOT NULL,
    occurred_at   DATETIME(6) NOT NULL,
    PRIMARY KEY (order_id)
);

CREATE INDEX idx_dispute_losses_loser ON dispute_losses (loser_user_id);
