-- 持久化 V10：escrow_orders 增加链上托管合约地址（S5 部署，2026-10-02）。
--
-- 背景：订单与链上托管合约实例要建立可查询的绑定——裁决出款（Resolve）、链上存证
-- （RecordEvidence）都以"该订单的合约地址"为投递目标；此前订单表没有任何链上列，
-- AdminVerdictController 只能对 resolveDispute 传 null（fail-closed 恒「未出款」）。
--
-- 口径：
--   chain_contract_address  合约地址（friendly base64url，48 字符量级；留 80 余量）。
--                           由部署编排在**发送部署消息之前**写入（先落库后发送）；
--                           NULL = 尚未部署（默认，不改变既有行行为）。
--
-- 【可移植性】遵循 V1 文末约束：不写 ENGINE/CHARSET/COLLATE；ALTER ... ADD COLUMN 在
-- MySQL 8 与测试用 H2 MODE=MySQL 下均可执行。

ALTER TABLE escrow_orders ADD COLUMN chain_contract_address VARCHAR(80);
