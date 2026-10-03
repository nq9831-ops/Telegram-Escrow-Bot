tolk 1.0

// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 telegram-escrow-bot contributors
//
// TON 托管合约（S5 / ET-10/11）——**非裁决路径**的受守卫状态机。
//
// 设计约束（逐条来自 SPEC 6 节与 09 材料，落地时保留这些不变量）：
//   - **无开发者提款函数**（"资金代码管"）；
//   - **每个分支先验前置状态**：TON 异步无序，乱序到达不得越级迁移（ET-58）；
//   - **减法前验余额**、**不提供无限额度授权**（SPEC 6.3：不制造授权面）；
//   - 升级入口（⑥ 方案 a，2026-09-30 开）：**提案 → 冷静期（72h）→ 执行** 的时间锁，
//     仅联邦可发起（多签在链下，A0-2）；时间锁在合约内校验；无「立即升级」路径。
//
// 范围（Wave 3 增量 1）：只做状态与守卫——`Deliver`/`Confirm`/`Refund`。
// **已实现**：非裁决资金路径——入金 `Fund`、交付 `Deliver`、确认出账 `Confirm`、
// 退款 `Refund`、以及出站被回弹时退回状态（`onBouncedMessage`）。
// **Jetton 计价的托管已实现**（入金 + 出账）：入金认我方 jetton 钱包发来的标准
// transfer_notification（TEP-74，0x7362d09c）；出账向**我方 jetton 钱包**发标准
// transfer（0xf8a7ea5），报文里指定真正收款方（卖方放款 / 买方退款）。
// **仍需注意三点**：㈠ 入金须附足量 TON（≥0.05）作为日后划转的燃料，否则 `InsufficientFeeTon`；
// ㈡ 出账随附的 TON 是**固定占位值**（费率/存储费池机制未定案，见 A0-3）；
// ㈢ 我方钱包地址由部署时链下预计算写入 `EscrowExtra.ownJettonWallet`。
//
// **已实现**：争议与裁决路径——`Dispute`（进入争议态）与 `Resolve`（联邦裁决放款/退款）；
// **升级入口**——`ProposeUpgrade`/`ApplyUpgrade`/`CancelUpgrade`（提案→冷静期→执行，
// 留痕 UpgradeProposed/Applied/Cancelled 事件，兼容读旧存储见 `loadExtraCompat`）；
// **紧急暂停（ET-21，2026-10-03）**——`Pause`/`Unpause`（仅联邦；幂等）：冻结资金与状态
// 推进，治理（升级三口）与存证**不拦**（暂停≠死锁）；白名单式前置守卫见 `pauseExemptOp`。
//
// **尚未实现**：
// ① 存储费池的显式额度（当前靠合约留存余额自付）。
//
// ⚠️ 验证状态：状态机由 `tests/EscrowContract.test.tolk` 覆盖（Acton 模拟网）；
// **链上行为、Gas、真实资金路径均未验证**（合约未部署）。

import "@acton/tlb/either"
import "types"

// 升级冷静期（秒）= 72h。写死为合约常量：改动须走升级本身（递归自洽）；72h 让审计窗口
// 覆盖一个周末——即使联邦 key 被破，冷静期内可察觉并 Cancel。
const UPGRADE_COOL_DOWN_SECS = 259200;
// 升级事件的外部日志 topic（"UPG"）——事件留痕的通道标识
const UPGRADE_LOG_TOPIC = 0x555047;

contract EscrowContract {
    author: "telegram-escrow-bot contributors"
    version: "0.1.0"
    description: "TON 托管合约（S5）——非裁决路径的受守卫状态机"
    storage: EscrowStorage
    incomingMessages: AllowedMessage
}

// 紧急暂停豁免表（ET-21）：暂停期间仅放行【治理与存证】——Pause/Unpause（暂停本身要能
// 反复确认与解除）、升级三口（暂停的用途正是"冻结业务 → 修复合约 → 恢复"，堵死治理会把
// 暂停变成死锁）、链上存证（无损记录）。其余（入金/状态推进/裁决/jetton 通知/信任根写入）
// 一律拒绝。**白名单式**：将来新增操作码默认被拦——漏加守卫不会静默放过（默认更安全）。
fun pauseExemptOp(op: uint32): bool {
    return op == 0x7d5a1015   // PauseMessage
        || op == 0x7d5a1016   // UnpauseMessage
        || op == 0x7d5a1007   // ProposeUpgradeMessage
        || op == 0x7d5a1008   // ApplyUpgradeMessage
        || op == 0x7d5a1009   // CancelUpgradeMessage
        || op == 0x7d5a1014;  // RecordEvidenceMessage
}

fun onInternalMessage(in: InMessage) {
    // 紧急暂停前置守卫（ET-21）：集中一处、白名单放行（见 pauseExemptOp）。
    // 空 body（部署/探测消息）无操作码——不受暂停管辖，交给 match 的 else 分支。
    // probe 是 slice 值拷贝（Tolk slice 值语义）——预读不消费 in.body（else 分支依赖它）。
    if (in.body.remainingBitsCount() >= 32) {
        var probe = in.body;
        val op = probe.loadUint(32);
        if (loadExtraCompat(EscrowStorage.load().extra).paused && !pauseExemptOp(op)) {
            throw Errors.Paused;
        }
    }

    val msg = lazy AllowedMessage.fromSlice(in.body);

    match (msg) {
        FundMessage => {
            var storage = lazy EscrowStorage.load();
            assert (in.senderAddress == storage.buyer) throw Errors.NotBuyer;
            // 资产必须匹配：TON 计价的托管不得被 jetton 通知当成入金（反之亦然）
            assert (loadExtraCompat(storage.extra).asset == 0) throw Errors.WrongAsset;
            assert (storage.state == 0) throw Errors.InvalidState;
            // 金额在部署时已确定（来自订单）；此处只验**实际到账**是否覆盖它——
            // 只托管一部分就进 LOCKED 会让"已锁定"名不副实。
            assert (in.valueCoins >= storage.amount) throw Errors.InsufficientFunds;
            // 多付必须退回：合约只该留下订单金额。只守卫 >= 而不退差额，会让买方多付的钱
            // 永久滞留（本合约无提款接口，等于吞没）。
            val surplus = in.valueCoins - storage.amount;
            if (surplus > 0) {
                createMessage({
                    bounce: BounceMode.RichBounce,
                    value: surplus,
                    dest: storage.buyer,
                }).send(SEND_MODE_PAY_FEES_SEPARATELY | SEND_MODE_BOUNCE_ON_ACTION_FAIL);
            }
            storage.state = 1;
            storage.save();
        }
        DeliverMessage => {
            var storage = lazy EscrowStorage.load();
            assert (in.senderAddress == storage.seller) throw Errors.NotSeller;
            assert (storage.state == 1) throw Errors.InvalidState;
            storage.state = 2;
            storage.save();
        }
        // ── 出账伴随 TON 与存储预算（费率定案，2026-10-02）─────────────────────
        // 本合约**不含任何平台费/扣费逻辑**：出账恒为 storage.amount 全额给收款方。
        // 平台费率走链下账本（FeePolicy），链上只保证「托管额全额可退/可放」。
        //  · TON 单（asset=0）：storage.amount 全额出账；
        //  · jetton 单（asset=1）：出账给【我方 jetton 钱包】随附 grams("0.05")——这是
        //    jetton transfer 及其通知链路的 gas 预算（按执行成本上界估），非费率；
        //    不足时 jetton 出账会失败（届时调大数值并重部署）；
        //  · 合约自身存储费以「入金超出 amount 的余部 + 部署附带余额」为预算自然留存
        //    （出账不退还余部）——耗尽前合约已处终态，可弃。
        // 变更以上任一数值都需重编译重部署（code hash 变）。
        ConfirmMessage => {
            var storage = lazy EscrowStorage.load();
            assert (in.senderAddress == storage.buyer) throw Errors.NotBuyer;
            assert (storage.state == 2) throw Errors.InvalidState;
            // 先落状态、再出账：TON 消息异步，先提交状态可避免重入期间重复放款
            storage.state = 4;
            storage.save();
            // 放款给卖方：TON 走原路；jetton 则把 jetton 划给卖方（发给**我方钱包**，报文指定收款方）
            if (loadExtraCompat(storage.extra).asset == 1) {
                createMessage({
                    bounce: BounceMode.RichBounce,
                    value: grams("0.05"),
                    dest: loadExtraCompat(storage.extra).ownJettonWallet,
                    body: JettonTransfer {
                        queryId: 0,
                        amount: storage.amount,
                        destination: storage.seller,
                        responseDestination: storage.buyer,
                        customPayload: null,
                        forwardTonAmount: 0,
                        forwardPayload: TlbEither<cell, Cell<cell>>.right(beginCell().endCell().toCell()),
                    },
                }).send(SEND_MODE_PAY_FEES_SEPARATELY | SEND_MODE_BOUNCE_ON_ACTION_FAIL);
            } else {
                createMessage({
                    bounce: BounceMode.RichBounce,
                    value: storage.amount,
                    dest: storage.seller,
                }).send(SEND_MODE_PAY_FEES_SEPARATELY | SEND_MODE_BOUNCE_ON_ACTION_FAIL);
            }
        }
        RefundMessage => {
            var storage = lazy EscrowStorage.load();
            assert (in.senderAddress == storage.buyer
                || in.senderAddress == storage.seller) throw Errors.NotParty;
            assert (storage.state == 1) throw Errors.InvalidState;
            storage.state = 5;
            storage.save();
            // 退款给买方（同上：先落状态、足额、回弹可退；jetton 计价则划回 jetton）
            if (loadExtraCompat(storage.extra).asset == 1) {
                createMessage({
                    bounce: BounceMode.RichBounce,
                    value: grams("0.05"),
                    dest: loadExtraCompat(storage.extra).ownJettonWallet,
                    body: JettonTransfer {
                        queryId: 0,
                        amount: storage.amount,
                        destination: storage.buyer,
                        responseDestination: storage.seller,
                        customPayload: null,
                        forwardTonAmount: 0,
                        forwardPayload: TlbEither<cell, Cell<cell>>.right(beginCell().endCell().toCell()),
                    },
                }).send(SEND_MODE_PAY_FEES_SEPARATELY | SEND_MODE_BOUNCE_ON_ACTION_FAIL);
            } else {
                createMessage({
                    bounce: BounceMode.RichBounce,
                    value: storage.amount,
                    dest: storage.buyer,
                }).send(SEND_MODE_PAY_FEES_SEPARATELY | SEND_MODE_BOUNCE_ON_ACTION_FAIL);
            }
        }
        DisputeMessage => {
            var storage = lazy EscrowStorage.load();
            assert (in.senderAddress == storage.buyer
                || in.senderAddress == storage.seller) throw Errors.NotParty;
            assert (storage.state == 1 || storage.state == 2) throw Errors.InvalidState;
            // 争议理由刻意**不上链**（EscrowExtra 布局兼容约束，见 types.tolk 的扩容策略）：
            // 理由在链下 escrow_orders 留痕，链上只记「已进入争议态」这一事实。
            storage.state = 3;
            storage.save();
        }
        ResolveMessage => {
            var storage = lazy EscrowStorage.load();
            // 方案 A（已批准）：链下多签裁决（EscrowVerdictGuard 全套）+ 链上联邦单方执行。
            // 链下裁决的签名集存库审计；链上信任根 = 本 storage.federation（部署参数）。
            assert (in.senderAddress == storage.federation) throw Errors.NotFederation;
            assert (storage.state == 3) throw Errors.InvalidState;
            assert (msg.outcome == 0 || msg.outcome == 1) throw Errors.InvalidMessage;
            // 先落状态、后出款（与 Confirm/Refund 同序：回弹时 onBouncedMessage 按 state 4/5 还原）
            var payTo = storage.seller;
            var backTo = storage.buyer;
            if (msg.outcome == 1) {
                payTo = storage.buyer;
                backTo = storage.seller;
                storage.state = 5;
            } else {
                storage.state = 4;
            }
            storage.save();
            if (loadExtraCompat(storage.extra).asset == 1) {
                createMessage({
                    bounce: BounceMode.RichBounce,
                    value: grams("0.05"),
                    dest: loadExtraCompat(storage.extra).ownJettonWallet,
                    body: JettonTransfer {
                        queryId: 0,
                        amount: storage.amount,
                        destination: payTo,
                        responseDestination: backTo,
                        customPayload: null,
                        forwardTonAmount: 0,
                        forwardPayload: TlbEither<cell, Cell<cell>>.right(beginCell().endCell().toCell()),
                    },
                }).send(SEND_MODE_PAY_FEES_SEPARATELY | SEND_MODE_BOUNCE_ON_ACTION_FAIL);
            } else {
                createMessage({
                    bounce: BounceMode.RichBounce,
                    value: storage.amount,
                    dest: payTo,
                }).send(SEND_MODE_PAY_FEES_SEPARATELY | SEND_MODE_BOUNCE_ON_ACTION_FAIL);
            }
        }
        JettonTransferNotification => {
            var storage = lazy EscrowStorage.load();
            // TEP-74 的两个地址**不是同一个**：通知由**我方自己的 jetton 钱包**发出，
            // 报文里的 `sender` 才是**上一持有人**（买方）。两处都要验、且都不采信对方：
            //   ① 实际发送者必须是我方钱包 —— 否则任何人都能伪造"已付款"通知（ET-50）
            assert (in.senderAddress == loadExtraCompat(storage.extra).ownJettonWallet) throw Errors.NotTrustedWallet;
            //   ② 资产必须是 jetton（TON 计价的托管不得被 jetton 通知锁定）
            assert (loadExtraCompat(storage.extra).asset == 1) throw Errors.WrongAsset;
            //   ③ 报文声明的上一持有人必须是买方 —— 否则第三方转入即可触发锁定
            assert (msg.sender == storage.buyer) throw Errors.NotBuyer;
            //   ④ 同资产下量纲一致，短付可比较、必须拒
            assert (msg.amount >= storage.amount) throw Errors.InsufficientFunds;
            //   ⑤ 必须带来足够 TON，供日后把 jetton 划出去时付转发费（fail-closed，不靠合约留存）
            assert (in.valueCoins >= grams("0.05")) throw Errors.InsufficientFeeTon;
            storage.state = 1;
            storage.save();
        }
        ProposeUpgradeMessage => {
            // 合约升级入口（⑥）：提案新代码。授权=联邦（与 Resolve 同信任根，多签在链下）；
            // 时间锁在**合约内**校验（Apply 分支）——链下冷静期防不住联邦 key 单点被破。
            // 升级是合约生命周期管理，与交易状态机**正交**：不检查 escrow state。
            var storage = lazy EscrowStorage.load();
            assert (in.senderAddress == storage.federation) throw Errors.NotFederation;
            var extra = loadExtraCompat(storage.extra);
            // 拒绝覆盖式提案：已有提案须先 Cancel——防「计时器热替换」（旧提案的冷静期
            // 不能被换成另一份代码接着用）
            assert (extra.upgrade == null) throw Errors.UpgradePending;
            val proposedAt = blockchain.now();
            extra.upgrade = UpgradeProposal {
                newCode: msg.newCode,
                proposedAt: proposedAt,
            }.toCell();
            storage.extra = extra.toCell();
            storage.save();
            createExternalLogMessage({
                dest: ExtOutLogBucket { topic: UPGRADE_LOG_TOPIC },
                body: UpgradeProposedEvent {
                    codeHash: msg.newCode.hash(),
                    proposedAt: proposedAt,
                },
            }).send(SEND_MODE_REGULAR);
        }
        ApplyUpgradeMessage => {
            var storage = lazy EscrowStorage.load();
            assert (in.senderAddress == storage.federation) throw Errors.NotFederation;
            var extra = loadExtraCompat(storage.extra);
            assert (extra.upgrade != null) throw Errors.NoUpgradeProposal;
            val proposal = extra.upgrade!!.load();
            // 时间锁：冷静期（72h）满才可执行。恰好等于 proposedAt + 冷静期即放行（>=）。
            assert (blockchain.now() >= proposal.proposedAt + UPGRADE_COOL_DOWN_SECS)
                throw Errors.UpgradeNotReady;
            val codeHash = proposal.newCode.hash();
            // setCodePostponed：action phase 调度、本 run 成功结束后生效（失败整笔回滚、
            // 提案保留可重试）；**只换 code 不动 data**——故新代码必须能读旧存储（loadExtraCompat）
            contract.setCodePostponed(proposal.newCode);
            extra.upgrade = null;
            storage.extra = extra.toCell();
            storage.save();
            createExternalLogMessage({
                dest: ExtOutLogBucket { topic: UPGRADE_LOG_TOPIC },
                body: UpgradeAppliedEvent {
                    codeHash: codeHash,
                    appliedAt: blockchain.now(),
                },
            }).send(SEND_MODE_REGULAR);
        }
        CancelUpgradeMessage => {
            var storage = lazy EscrowStorage.load();
            assert (in.senderAddress == storage.federation) throw Errors.NotFederation;
            var extra = loadExtraCompat(storage.extra);
            // 显式报错不静默：无提案时 Cancel 也要给出确定信号（审计可辨「撤了个空」）
            assert (extra.upgrade != null) throw Errors.NoUpgradeProposal;
            val proposal = extra.upgrade!!.load();
            val codeHash = proposal.newCode.hash();
            extra.upgrade = null;
            storage.extra = extra.toCell();
            storage.save();
            createExternalLogMessage({
                dest: ExtOutLogBucket { topic: UPGRADE_LOG_TOPIC },
                body: UpgradeCancelledEvent {
                    codeHash: codeHash,
                    cancelledAt: blockchain.now(),
                },
            }).send(SEND_MODE_REGULAR);
        }
        SetJettonWalletMessage => {
            // 两阶段 W 的第 2 步（部署时为零哨兵，见 types.tolk 的说明与链下部署编排）：
            // 联邦按 (jetton master, 本合约地址) 链下推导出真实钱包地址后写入本条。
            // **仅联邦**（与 Resolve/升级同信任根）；**仅从零设置一次**——写入后即为
            // transfer_notification 的信任锚，之后不可替换（替换=信任根被事后掉包）。
            var storage = lazy EscrowStorage.load();
            assert (in.senderAddress == storage.federation) throw Errors.NotFederation;
            var extra = loadExtraCompat(storage.extra);
            assert (isZeroAddress(extra.ownJettonWallet)) throw Errors.JettonWalletAlreadySet;
            // 拒把零地址当"写入"：既不构成有效设置，又会把哨兵语义烧掉（之后的真实设置被拒）
            assert (!isZeroAddress(msg.wallet)) throw Errors.InvalidMessage;
            extra.ownJettonWallet = msg.wallet;
            storage.extra = extra.toCell();
            storage.save();
        }
        RecordEvidenceMessage => {
            // 链上存证（ET-43 链上腿）：买方/卖方/联邦可记；不校验 escrow 状态
            // （链下举证窗口已管时效；交付证明将来复用同一路径）。
            var storage = lazy EscrowStorage.load();
            assert (in.senderAddress == storage.buyer
                || in.senderAddress == storage.seller
                || in.senderAddress == storage.federation) throw Errors.NotParty;
            var extra = loadExtraCompat(storage.extra);
            // key=哈希 → value=提交时刻：同哈希重复提交幂等（覆盖），无重复项。
            extra.evidence.set(msg.evidenceHash, blockchain.now());
            storage.extra = extra.toCell();
            storage.save();
        }
        PauseMessage => {
            // 紧急暂停（ET-21）：仅联邦（与 Resolve/升级/SetJettonWallet 同信任根）；
            // 幂等——重复 Pause 无副作用（暂停期间消息重传最频繁，报错只添噪音）。
            var storage = lazy EscrowStorage.load();
            assert (in.senderAddress == storage.federation) throw Errors.NotFederation;
            var extra = loadExtraCompat(storage.extra);
            if (!extra.paused) {
                extra.paused = true;
                storage.extra = extra.toCell();
                storage.save();
            }
        }
        UnpauseMessage => {
            // 解除暂停（ET-21）：仅联邦；幂等。
            var storage = lazy EscrowStorage.load();
            assert (in.senderAddress == storage.federation) throw Errors.NotFederation;
            var extra = loadExtraCompat(storage.extra);
            if (extra.paused) {
                extra.paused = false;
                storage.extra = extra.toCell();
                storage.save();
            }
        }
        else => {
            // 空消息体不视为错误（部署/探测消息）；带体但无法识别才是错误
            assert (in.body.isEmpty()) throw Errors.InvalidMessage;
        }
    }
}

/// 零地址哨兵（workchain 0 + 256-bit 全零）：部署时 `ownJettonWallet` 的初始值。
/// 两阶段 W 设置（SetJettonWallet）以它为"未设置"的判据——见 types.tolk 的说明。
fun isZeroAddress(a: address): bool {
    return a == address("0:0000000000000000000000000000000000000000000000000000000000000000");
}

fun onBouncedMessage(_in: InMessageBounced) {
    // 出站资金被回弹（目标不可达/拒收）：把状态退回去，避免出现「账上已放款、钱却没出去」。
    // 这是本合约唯一的地方：钱没走成，状态就不能停在已出账的那一格。
    var storage = lazy EscrowStorage.load();
    if (storage.state == 4) {
        storage.state = 2;   // RELEASED → DELIVERED：可重试放款
        storage.save();
    } else if (storage.state == 5) {
        storage.state = 1;   // REFUNDED → LOCKED：可重试退款
        storage.save();
    }
}

get fun escrowState(): int {
    val storage = lazy EscrowStorage.load();
    return storage.state;
}

get fun buyer(): address {
    val storage = lazy EscrowStorage.load();
    return storage.buyer;
}

get fun seller(): address {
    val storage = lazy EscrowStorage.load();
    return storage.seller;
}

// 升级提案状态（⑥）：(newCode hash, proposedAt)，无提案返回 (0, 0)。
// 给链下编排/监控读取冷静期进度用（Java 侧 EscrowUpgradeService 亦以此为准）。
get fun upgradeStatus(): (int, int) {
    val storage = lazy EscrowStorage.load();
    val upgrade = loadExtraCompat(storage.extra).upgrade;
    if (upgrade == null) {
        return (0, 0);
    }
    val proposal = upgrade!!.load();
    return (proposal.newCode.hash(), proposal.proposedAt);
}

// 两阶段 W 的当前值（链下核验 set 是否落地；零地址=尚未设置）。
get fun ownJettonWallet(): address {
    val storage = lazy EscrowStorage.load();
    return loadExtraCompat(storage.extra).ownJettonWallet;
}

// 链上存证查询：第一项 found（1/0），found=1 时第二项为提交时刻；未找到 (0, 0)。
get fun evidenceOf(hash: uint256): (int, int) {
    val storage = lazy EscrowStorage.load();
    val r = loadExtraCompat(storage.extra).evidence.get(hash);
    if (r.isFound) {
        return (1, r.loadValue());
    }
    return (0, 0);
}

// 证据条数（map 无 size，遍历计数——核验/测试用）。
get fun evidenceCount(): int {
    val storage = lazy EscrowStorage.load();
    var m = loadExtraCompat(storage.extra).evidence;
    var count = 0;
    var r = m.findFirst();
    while (r.isFound) {
        count += 1;
        r = m.iterateNext(r);
    }
    return count;
}
