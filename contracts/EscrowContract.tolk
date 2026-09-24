;; SPDX-License-Identifier: AGPL-3.0-only
;; Copyright (C) 2026 telegram-escrow-bot contributors
;; TON 托管合约（S5 / ET-10/11/52/53）——Tolk 语言。
;;
;; ⚠️ 验证状态：**仅源码与注释，编译/部署/链上行为均未验证**（本地无 TON 工具链）。
;;    落地前必须：① 定案多签语义（待决 C1）；② 用 Blueprint/Acton 编译并跑测试；
;;    ③ Gas 基线报告（blueprint test --gas-report --snapshot）；④ 第三方审计（ET-20）。
;;
;; 设计约束（来自 SPEC 6 节与 09 材料，逐条落实）：
;;   - **无开发者提款函数**（核心原则「资金代码管」）；
;;   - **每个消息处理先验前置状态**（TON 异步无序：乱序到达不得越级迁移，ET-58）；
;;   - **减法前验余额**（coins 类型不为负，仍显式 throw_unless 防逻辑错）；
;;   - **Jetton 验证**：校验发送者合约地址，不信任 transfer_notification 声称（ET-50）；
;;   - **交付证明哈希先加盐再哈希**（ET-57 预映像防护——salt 由买家随证明一并提交）；
;;   - **存储费池**：fund 时预留 storageFund，防止存储不足被冻结（04 材料）。

;; ---- 存储（Tolk struct 自动序列化）----
;; struct EscrowStorage {
;;     buyer: address
;;     seller: address
;;     federation: address      ;; 联邦仲裁节点（多签必含方，C1 语义见待决）
;;     amount: coins
;;     state: uint8             ;; 0=OPEN 1=LOCKED 2=DELIVERED 3=DISPUTED 4=RELEASED 5=REFUNDED
;;     maintenancePeriod: uint32
;;     maintenanceDeadline: uint64
;;     disputeDeadline: uint64
;;     deliveryProofHash: uint256   ;; 加盐后的交付证明哈希（ET-52/53）
;;     storageFund: coins           ;; 存储费池
;; }

;; ---- 消息路由（union + match；每分支先验前置状态）----
;; fun onInternalMessage(in: InMessage) {
;;     val msg = lazy Msg.fromCell(in.body);
;;     match (msg) {
;;         FundMessage     => handleFund(in)          ;; OPEN→LOCKED：买方入金 + 存储费
;;         DeliveryMessage => handleDeliver(in)       ;; LOCKED→DELIVERED：卖方标记交付
;;         ConfirmMessage  => handleConfirm(in)       ;; DELIVERED→RELEASED：买方确认收货（带 salted proofHash）
;;         DisputeMessage  => handleDispute(in)       ;; LOCKED/DELIVERED→DISPUTED：任一方发起
;;         ResolveMessage  => handleResolve(in)       ;; DISPUTED→RELEASED/REFUND：2/3 多签（必含联邦）
;;         RefundMessage   => handleRefund(in)        ;; LOCKED→REFUND：超时/协商退款
;;         else => throw 0xffff
;;     }
;; }

;; ---- 关键守卫（伪代码，落地时按 Tolk 1.x 语法核对）----
;; handleConfirm:
;;   throw_unless(401, in.senderAddress == storage.buyer)          ;; 仅买方可确认
;;   throw_unless(402, storage.state == 2)                         ;; 前置状态：DELIVERED（乱序安全）
;;   ;; 预映像防护：proofHash = hash(salt ++ evidence)，salt 由买家提交
;;   storage.deliveryProofHash = hash_concat(msg.salt, msg.evidenceHash)
;;   storage.maintenanceDeadline = blockchain.logicalTime() + storage.maintenancePeriod
;;   storage.state = 4
;;   send_remaining_with_storage_fund(in, storage.seller, storage.amount)   ;; 释放 + 预留存储
;;
;; handleResolve（多签 2/3，必含联邦——C1：实质为联邦裁断，已向运营者披露）:
;;   throw_unless(403, verify_multisig(msg.signatures, {buyer, seller, federation}, min = 2))
;;   throw_unless(404, contains_federation(msg.signatures))
;;   throw_unless(405, storage.state == 3)                         ;; 仅 DISPUTED 可裁决
;;   match (msg.resolution) {
;;     0 => refund_to(in, storage.buyer)    ;; 退款
;;     1 => release_to(in, storage.seller)  ;; 放款
;;     else => throw 0xfffe                 ;; Tolk 版本落地时补按比例分配
;;   }
;;
;; handleFund（存储费池 + Jetton 验证）:
;;   throw_unless(406, storage.state == 0)
;;   ;; Jetton 场景：必须校验 in.senderAddress == trustedJettonWallet（ET-50，防 Fake Jetton）
;;   storage.storageFund += 0.003_TON       ;; 存储费预留（04 材料）
;;   storage.state = 1
;;
;; ---- 不提供的接口（刻意）----
;; - 开发者提款 / 升级代理（ET-56 默认关闭，见待决 C2）/ 无限额度授权（SPEC 6.3：不制造授权面）
