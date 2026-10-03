// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 telegram-escrow-bot contributors
//
// EscrowContract 的类型与错误码（Wave 3 增量 1：受守卫的状态机）。
// 消息须带显式 32 位操作码前缀——wrapper 与 match 都依赖它。

import "@acton/tlb/either"

enum Errors {
    InvalidMessage = 0xFFFF
    InvalidState = 0x1002
    NotSeller = 0x1003
    NotBuyer = 0x1004
    NotParty = 0x1005
    InsufficientFunds = 0x1006
    NotTrustedWallet = 0x1007
    WrongAsset = 0x1009
    InsufficientFeeTon = 0x100a
    // 保留未用：jetton 路径若将来再次被关闭（例如出账被回退），可复用此码明确拒绝入金
    JettonNotSupported = 0x1008
    // 争议裁决（S5）：Resolve 仅限联邦裁决授权方
    NotFederation = 0x100b
    // 合约升级入口（⑥）：提案 → 冷静期 → 执行 的时间锁生命周期
    UpgradePending = 0x100c      // 已有提案未清，须先 Cancel（拒绝覆盖式提案，防计时器热替换）
    NoUpgradeProposal = 0x100d   // Apply/Cancel 时无提案——显式报错，不静默
    UpgradeNotReady = 0x100e     // 冷静期未满：now < proposedAt + 冷静期
    // 两阶段 W 设置（S5 部署）：部署时 ownJettonWallet=零哨兵，部署后联邦写入真实值。
    // 已有非零值后再设置 = 试图替换信任根——拒绝（一次性语义）。
    JettonWalletAlreadySet = 0x100f
    // 紧急暂停（ET-21）：暂停期间资金与状态推进一律拒绝（治理/存证通道不拦）
    Paused = 0x1010
}

// 标准 Jetton transfer（TEP-74 `transfer`）——**发给自己的 jetton 钱包**，报文里指定真正收款方。
// 尾部 `forward_payload` 是 `(Either Cell ^Cell)`，此处取 **right（引用）** 形态装空 payload；
// 我不用它，但**必须写全**：钱包侧是整条 loadMessage，缺字段会解析失败、转账静默不生效。
struct (0xf8a7ea5) JettonTransfer {
    queryId: uint64
    amount: coins
    destination: address
    responseDestination: address
    customPayload: Cell<void>?                    // (Maybe ^Cell)，此处 null
    forwardTonAmount: coins                       // 我方不需要回执，故 0
    forwardPayload: TlbEither<cell, Cell<cell>>   // (Either Cell ^Cell)，取 right 空 payload
}

// 升级提案（⑥）：待执行的新代码 + 提案时刻。整个提案存于 `EscrowExtra.upgrade` 引用。
struct UpgradeProposal {
    newCode: cell           // 待执行的新代码（整 cell）
    proposedAt: uint32      // 提案时刻（unix 秒）——冷静期从这里起算
}

// 放不下的字段走引用：基础字段已 933 bit 左右，再加一个 address（267）就超单 cell 1023 上限
// （编译器实测 1080..1200）。按编译器建议拆 `Cell<EscrowExtra>`，**不用** @overflow1023_policy
// （那是把失败推到运行时）。读时须显式 `.load()`——fromCell 不深读引用。
struct EscrowExtra {
    // **我方（本合约）自己的 jetton 钱包**地址——TEP-74 规定：transfer_notification 由
    // **收款方自己的钱包**发出，故这才是"可信发送者"。部署时由链下按
    // （jetton master, 本合约地址）算出后写入。**不是**买方的钱包地址。
    ownJettonWallet: address
    // 托管计价资产：0=TON 1=JETTON。决定**入金口径**与**出账方式**——
    // 缺了它，"收到 jetton 通知"与"收到 TON 入金"无法区分，金额量纲也无从对齐。
    asset: uint8
    // 升级提案（⑥）：Maybe ^UpgradeProposal，无提案 = null。**旧格式 extra cell 没有这
    // 1 bit 标志**，直接 `EscrowExtra.fromCell` 读旧数据会因缺位抛错——所有消费点**一律走
    // `loadExtraCompat`**（缺失字段给默认值），这是 UNDECIDABLE-DECISIONS ⑥ 硬约束 2 的落点。
    upgrade: Cell<UpgradeProposal>?
    // 链上存证（ET-43 链上腿）：map<证据哈希, 提交时刻>——key 即哈希，同哈希重复提交
    // 天然幂等（覆盖）；map 自身表达"空"（null），无需再包 Maybe。
    // 第三段扩展：旧格式（截至 upgrade 的两种）读到这里时 0 bit 剩余——loadExtraCompat
    // 按 remainingBitsCount 判定在不在，缺则给空 map（再往后加字段照此办理）。
    evidence: map<uint256, uint32>
    // 第四段扩展（ET-21）：紧急暂停位。旧格式（截至 evidence）读到这里 0 bit 剩余——
    // loadExtraCompat 缺位给 false。true = 资金与状态推进全部被拒（Pause/Unpause 仅联邦）。
    paused: bool
}

// `extra` 引用**已落地**（2026-09-25），当前装 `ownJettonWallet`、`asset`、`upgrade`
// （升级提案，⑥）与 `evidence`（链上存证，2026-10-02）。旧数据（缺后续字段）由
// `loadExtraCompat` 兼容读取。
// 后续要加的字段（maintenancePeriod/maintenanceDeadline/deliveryProofHash/storageFund 等）
// **继续往 `EscrowExtra` 里加**：基础结构体已贴近单 cell 1023 bit 上限
// （编译器实测：直接加一个 address 就 1080..1200），故**不得**再往基础结构体加字段，
// 也**不得**用 @overflow1023_policy("suppress") 硬压——那是把失败推到运行时。

struct EscrowStorage {
    buyer: address
    seller: address
    federation: address
    amount: coins
    state: uint8             // 0=OPEN 1=LOCKED 2=DELIVERED 3=DISPUTED 4=RELEASED 5=REFUNDED
    extra: Cell<EscrowExtra>
}

fun EscrowStorage.load(): EscrowStorage {
    return EscrowStorage.fromCell(contract.getData());
}

fun EscrowStorage.save(self) {
    contract.setData(self.toCell());
}

// 容错读取 EscrowExtra：**旧格式**（275 bit/0 ref，升级前的两字段布局）与**新格式**
// （尾部多 1 bit Maybe 标志，`upgrade: null` 时 276 bit/0 ref）都读得动——缺失字段给默认值。
// 硬约束（UNDECIDABLE-DECISIONS ⑥ 第 2 条）：新代码必须能读旧 EscrowExtra。
// 严格 `EscrowExtra.fromCell` 在旧数据上会因缺位抛错、资金路径全崩，故所有消费点
// **一律走本函数**，不得回退 `extra.load()`。
fun loadExtraCompat(extra: Cell<EscrowExtra>): EscrowExtra {
    var s = extra.beginParse();
    val ownJettonWallet = s.loadAddress();
    val asset = s.loadUint(8);
    var upgrade: Cell<UpgradeProposal>? = null;
    // 段 2（升级提案）：更旧格式到此 0 bit 剩余；loadMaybeRef 自己消费 Maybe 标志位：
    // null=无提案，非 null=提案引用（裸 cell，经 fromCell/toCell 类型化回 Cell<UpgradeProposal>）。
    if (s.remainingBitsCount() > 0) {
        val refCell = s.loadMaybeRef();
        if (refCell != null) {
            upgrade = UpgradeProposal.fromCell(refCell!!).toCell();
        }
    }
    // 段 3（链上存证 map）：旧格式（截至 upgrade 的两种）到此 0 bit 剩余——缺则空 map。
    // map 字段的序列化恰是 Maybe cell（dict root = cell?），loadMaybeRef 即读这一形态；
    // null 经 createMapFromLowLevelDict 得空 map（dict 与 cell? 同构）。
    var evidence: map<uint256, uint32> = [];
    if (s.remainingBitsCount() > 0) {
        val evidenceDict = s.loadMaybeRef();
        evidence = createMapFromLowLevelDict<uint256, uint32>(evidenceDict);
    }
    // 段 4（紧急暂停，ET-21）：更旧格式到此 0 bit 剩余——缺则未暂停（false）。
    var paused = false;
    if (s.remainingBitsCount() > 0) {
        paused = s.loadUint(1) == 1;
    }
    return EscrowExtra {
        ownJettonWallet: ownJettonWallet,
        asset: asset,
        upgrade: upgrade,
        evidence: evidence,
        paused: paused,
    };
}

// ---- 非裁决路径消息（增量 1）；Dispute/Resolve 随 S5 已实现 ----
struct (0x7d5a1001) FundMessage {}         // OPEN → LOCKED（仅买方，且到账须 ≥ amount）
struct (0x7d5a1002) DeliverMessage {}      // LOCKED → DELIVERED（仅卖方）
struct (0x7d5a1003) ConfirmMessage {}      // DELIVERED → RELEASED（仅买方，出账给卖方）
struct (0x7d5a1004) RefundMessage {}       // LOCKED → REFUNDED（任一方，出账给买方）
struct (0x7d5a1005) DisputeMessage {}      // LOCKED/DELIVERED → DISPUTED（当事任一方；理由链下留痕，不占 storage）
struct (0x7d5a1006) ResolveMessage {       // DISPUTED → RELEASED/REFUNDED（仅联邦——方案 A：链下多签裁决 + 链上联邦单方执行）
    outcome: uint8                         // 0=RELEASE 放款卖方（→态 4）；1=REFUND 退款买方（→态 5）
}

// ---- 合约升级入口（⑥）：提案 → 冷静期 → 执行 的时间锁生命周期 ----
// 授权 = storage.federation（与 Resolve 同信任根；多签在链下，A0-2 已拍板不做链上多签）。
// 时间锁**在合约内**校验（now ≥ proposedAt + 冷静期），链下冷静期防不住联邦 key 单点被破。
struct (0x7d5a1007) ProposeUpgradeMessage {   // 提案新代码（仅联邦；已有提案须先 Cancel）
    newCode: cell                             // 待执行的新代码
}
struct (0x7d5a1008) ApplyUpgradeMessage {}    // 冷静期满后执行 setCodePostponed（仅联邦）
struct (0x7d5a1009) CancelUpgradeMessage {}   // 撤销提案（仅联邦，随时可撤）

// ---- 紧急暂停（ET-21，2026-10-03）：冻结业务 → 修复合约 → 恢复 ----
// 仅联邦（与 Resolve/升级/SetJettonWallet 同信任根）；**幂等**（重复/重放无副作用——
// 不需要"已暂停"报错：暂停期间恰是消息重传最频繁的时候，报错只会造成噪音）。
// 暂停语义见 requireNotPaused：拦资金与状态推进，不拦治理（升级三口）与存证。
struct (0x7d5a1015) PauseMessage {}       // 紧急暂停：仅联邦；幂等
struct (0x7d5a1016) UnpauseMessage {}     // 解除暂停：仅联邦；幂等

// ---- S5 部署与存证（2026-10-02 落地）----
// 两阶段 W 设置：部署时 ownJettonWallet=零哨兵（地址=H(code,data) 与 W=J(master,address)
// 是自引用方程——伪随机固定点迭代不收敛）；部署后联邦按 (master, 本合约地址) 链下推导
// 出真实钱包地址并写入本条消息。**仅联邦、仅从零设置一次**——之后即为信任根，不可替换。
struct (0x7d5a1013) SetJettonWalletMessage {
    wallet: address
}
// 链上存证（ET-43 链上腿）：证据哈希记进 EscrowExtra.evidence（key=哈希 → value=提交时刻）。
// 发送者限买方/卖方/联邦；不限状态（链下举证窗口已管；交付证明将来复用同一路径）。
struct (0x7d5a1014) RecordEvidenceMessage {
    evidenceHash: uint256
}

// 升级事件（⑥「升级事件留痕」）：只出不进，**不进 AllowedMessage**；
// 走外部日志消息（createExternalLogMessage），测试以 toEmitExternalMessage<事件> 断言。
struct (0x7d5a1010) UpgradeProposedEvent {
    codeHash: uint256                         // 提案的新代码 hash（留痕记 hash 不进整码）
    proposedAt: uint32
}
struct (0x7d5a1011) UpgradeAppliedEvent {
    codeHash: uint256
    appliedAt: uint32
}
struct (0x7d5a1012) UpgradeCancelledEvent {
    codeHash: uint256                         // 被撤销提案的代码 hash（审计留痕）
    cancelledAt: uint32
}

// 标准 Jetton transfer_notification（TEP-74）——由**收款方自己的 jetton 钱包**发出，
// 报文里的 `sender` 是**上一持有人**（即买方），二者不是同一个地址，都要验。
struct (0x7362d09c) JettonTransferNotification {
    queryId: uint64
    amount: coins            // jetton 数量（**量纲与 TON 的 amount 不同**，不可直接相较）
    sender: address          // 上一持有人 = 买方
}

type AllowedMessage =
    | FundMessage
    | DeliverMessage
    | ConfirmMessage
    | RefundMessage
    | DisputeMessage
    | ResolveMessage
    | ProposeUpgradeMessage
    | ApplyUpgradeMessage
    | CancelUpgradeMessage
    | SetJettonWalletMessage
    | RecordEvidenceMessage
    | JettonTransferNotification
    | PauseMessage
    | UnpauseMessage
