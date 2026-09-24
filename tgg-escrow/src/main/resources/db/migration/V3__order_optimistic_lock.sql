-- 持久化 V3：订单乐观锁版本列（可移植 DDL，见 V1 的约束：不写 ENGINE/CHARSET）
--
-- 背景：订单状态迁移是「读快照 → 判状态 → 改状态 → 写回」。没有版本列时，
-- 两个并发操作者读到同一快照，后写者会静默覆盖先写者——典型反例是买方 cancel
-- 覆盖卖方的 markDelivered，留下状态自相矛盾的订单，击穿 markCancelled 的资金安全边界。
--
-- 对应实体侧 EscrowOrder 的 @Version 字段；DEFAULT 0 保证存量行可直接迁移。

ALTER TABLE escrow_orders ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
