-- 持久化 V13：tonpay_references——TON Pay 支付引用登记与结算痕迹（ET-31/32）。
--
-- 背景：TON Pay 用于「买方锁资」路径（可选，P1）。客户端 createTonPayTransfer 返回
-- {reference, bodyBase64Hash}，服务端登记后，TON Pay 支付完成会 POST webhook（六步验证见
-- TonPayWebhookController）。本表是 webhook 的第 3 步（reference 匹配订单）与第 4 步
-- （防重复）的持久载体；settled_* 是第 6 步（标记完成 + 记 txHash）的落点。
--
-- 口径：
--   reference 唯一——同一支付凭证只能归属一个订单一次；
--   settled_at 非空 = 该笔已结算（重放 webhook 直接回成功，不重复处理）；
--   金额/币种在结算时定格（对账与第 5 步核对的痕迹）。
--
-- 【可移植性】遵循 V1 文末约束：不写 ENGINE/CHARSET/COLLATE；MySQL 8 与 H2 MODE=MySQL 均可执行。

CREATE TABLE tonpay_references (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    reference VARCHAR(128) NOT NULL,
    body_base64_hash VARCHAR(128),
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    tx_hash VARCHAR(128),
    settled_amount DECIMAL(24,8),
    settled_currency VARCHAR(16),
    settled_at TIMESTAMP(6),
    CONSTRAINT uk_tonpay_reference UNIQUE (reference)
);
