-- 持久化 V11：escrow_orders 增加链上金额与用户 TON 钱包地址（TON Connect 资金链，2026-10-02）。
--
-- 背景：资金路径改由「用户自己的钱包」签名发送（TON Connect），两样东西必须落库：
--   ① chain_amount_nano  托管金额（链上最小单位，nanoton 十进制字符串）——此前只在 admin
--      部署请求体里一闪而过；用户钱包发 Fund 时必须付「恰好这个数」，前端一律从后端取
--      （防各自换算出错）。字符串列（VARCHAR(32)）：nanoton 可达 2^128 量级（jetton decimals
--      因币种而异），超出 Java long 与 JSON 数字安全整数区间——存十进制串杜绝精度事故。
--   ② buyer/seller_ton_address  用户在 TON Connect 里连接的钱包地址（部署前可更新=重连换包；
--      部署后不可改绑——地址已写进链上 storage，Fund 守卫 sender==storage.buyer，
--      见 EscrowOrder.attachBuyerTonAddress 的语义注释）。
--
-- 【可移植性】遵循 V1 文末约束：不写 ENGINE/CHARSET/COLLATE；ALTER ... ADD COLUMN 在
-- MySQL 8 与测试用 H2 MODE=MySQL 下均可执行。

ALTER TABLE escrow_orders ADD COLUMN chain_amount_nano VARCHAR(32);
ALTER TABLE escrow_orders ADD COLUMN buyer_ton_address VARCHAR(80);
ALTER TABLE escrow_orders ADD COLUMN seller_ton_address VARCHAR(80);
