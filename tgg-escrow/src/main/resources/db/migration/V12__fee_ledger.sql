-- 持久化 V12：fee_ledger——放款费用台账（ET-29/30 的链下账本落点）。
--
-- 背景：费率已定案（FeePolicy）且有逐笔披露，但核算结果此前只出现在用户回执里、不落库；
-- 合约侧明确「平台费率走链下账本，链上只保证托管额全额可退/可放」——台账是该决定下
-- 费用的唯一持久痕迹（对账：每笔交易收了多少平台费）。
--
-- 口径：
--   order_id 唯一——每单至多一条（重放/重试幂等，重复入账被去重）；
--   platform_fee 为实际收取（首单豁免时为 0）；first_order_waived 区分「豁免发生」
--   与「费率本为 0」两种产品语义（对账时需要能分开看）；
--   recorded_at 由数据库生成（DEFAULT CURRENT_TIMESTAMP(6)），实体只读。
--
-- 【可移植性】遵循 V1 文末约束：不写 ENGINE/CHARSET/COLLATE；本脚本在 MySQL 8 与
-- 测试用 H2 MODE=MySQL 下均可执行。

CREATE TABLE fee_ledger (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    currency VARCHAR(16) NOT NULL,
    gross_amount DECIMAL(24,8) NOT NULL,
    platform_fee DECIMAL(24,8) NOT NULL,
    seller_net DECIMAL(24,8) NOT NULL,
    first_order_waived BOOLEAN NOT NULL DEFAULT FALSE,
    recorded_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_fee_ledger_order UNIQUE (order_id)
);
