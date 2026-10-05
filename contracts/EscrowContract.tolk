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
// transfer（0x0f8a7ea5，必须写满 32 位——见 types.tolk 的 tag 警示），报文里指定真正收款方（卖方放款 / 买方退款）。
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

// 升级冷静期（秒）= 7 天（2026-10-04 加固档：72h → 7 天）。写死为合约常量：改动须走升级
// 本身（递归自洽）；7 天让审计窗口覆盖完整一周——即使联邦 key 被破，冷静期内可察觉并
// Cancel；叠加"提案即自动 Pause + 双方逃生票"（见 ProposeUpgrade/EscapeVote）构成三防线。
const UPGRADE_COOL_DOWN_SECS = 604800;
// 裁决冷静期（秒）= 24h（2026-10-04 防偏袒加固，A）。裁决从"联邦单方即时执行"改为
// "提案 → 24h 公示 → 任何人可执行"：方向不可热改、每步留痕、必须当事方二签（B）。
const RESOLVE_COOL_DOWN_SECS = 86400;
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
        || op == 0x7d5a1014   // RecordEvidenceMessage
        || op == 0x7d5a1017   // ProposeResolveMessage——升级暂停期争议仍须可裁决（防「谈不拢即锁死」；裁决自带 24h 冷静期+二签双保护）
        || op == 0x7d5a1018   // ExecuteResolveMessage——执行面同上（冷静期满后任何人可代发，方向不可改）
        || op == 0x7d5a1019   // CancelResolveMessage——撤提案是安全方向，不受暂停拦
        || op == 0x7d5a101a;  // EscapeVoteMessage——逃生票（用户的钱）绝不能被暂停锁死
}

fun onInternalMessage(in: InMessage) {
    // 紧急暂停前置守卫（ET-21）：集中一处、白名单放行（见 pauseExemptOp）。
    // 空 body（部署/探测消息）无操作码——不受暂停管辖，交给 match 的 else 分支。
    // probe 是 slice 值拷贝（Tolk slice 值语义）——预读不消费 in.body（else 分支依赖它）。
    if (in.body.remainingBitsCount() >= 32) {
        var probe = in.body;
        val op = probe.loadUint(32);
        val extra = loadExtraCompat(EscrowStorage.load().extra);
        // 2026-10-05 审计修复（H6a）：升级提案存续 = 冻结（派生式）——提案的冻结不再落实体 paused，
        // Cancel/Apply 清 upgrade 即自动解冻；手动 Pause（paused=true）保持独立、不被升级流程误清。
        if ((extra.paused || extra.upgrade != null) && !pauseExemptOp(op)) {
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
        // ── 出账伴随 TON 与存储预算（费率定案，2026-10-02；2026-10-04 链上扣费上链）──
        // 链上扣费（方案 B）：放款类出口（Confirm / Resolve RELEASE）在 payOut 内扣 fee——
        // 配置齐全（feeAddress+feeRateBps>0）时收款方得 amount−fee、费地址得 fee；
        // 未配置时恒为全额（兼容护栏）。退款类出口（Refund / REFUND / EscapeVote）不扣。
        //  · TON 单（asset=0）：storage.amount 全额出账；
        //  · jetton 单（asset=1）：出账给【我方 jetton 钱包】随附 grams("0.05")——这是
        //    jetton transfer 及其通知链路的 gas 预算（按执行成本上界估），非费率；
        //    不足时 jetton 出账会失败（届时调大数值并重部署）；
        //  · 合约自身存储费以「部署附带余额」为预算自然留存（入金超出 amount 的余部
        //    已由 Fund 分支全额退回买方，见上）——耗尽前合约已处终态，可弃。
        // 变更以上任一数值都需重编译重部署（code hash 变）。
        ConfirmMessage => {
            var storage = lazy EscrowStorage.load();
            assert (in.senderAddress == storage.buyer) throw Errors.NotBuyer;
            assert (storage.state == 2) throw Errors.InvalidState;
            // 先落状态、再出账：TON 消息异步，先提交状态可避免重入期间重复放款
            storage.state = 4;
            storage.save();
            // 放款给卖方（chargeFee=true：正常放款收平台费——方案 B 链上扣）
            payOut(storage, storage.seller, storage.buyer, true);
        }
        RefundMessage => {
            var storage = lazy EscrowStorage.load();
            assert (in.senderAddress == storage.buyer
                || in.senderAddress == storage.seller) throw Errors.NotParty;
            assert (storage.state == 1) throw Errors.InvalidState;
            storage.state = 5;
            storage.save();
            // 退款给买方（chargeFee=false：退款不扣费；jetton 计价则划回 jetton）
            payOut(storage, storage.buyer, storage.seller, false);
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
        ProposeResolveMessage => {
            // 裁决提案（A+B，2026-10-04 防偏袒加固）：联邦提出 + 当事方二签；提案入链供双方
            // 核查，冷静期满后由任何人执行（ExecuteResolve）。**方向一次定死**——想改必须
            // Cancel 后重提案（再等一个冷静期）：偏袒的时间成本与留痕都翻倍。
            var storage = lazy EscrowStorage.load();
            assert (in.senderAddress == storage.federation) throw Errors.NotFederation;
            assert (storage.state == 3) throw Errors.InvalidState;
            assert (msg.outcome == 0 || msg.outcome == 1) throw Errors.InvalidMessage;
            var extra = loadExtraCompat(storage.extra);
            assert (extra.resolveProposal == null) throw Errors.ResolvePending;
            // 当事方二签（B）：对 hash(op | outcome | 本合约地址) 验 Ed25519——签名绑定
            // "这一单、这一方向"，换单/换方向/换合约的重放一律失效。公钥未配置 = fail-closed 拒绝。
            val payload = beginCell()
                .storeUint(0x7d5a1017, 32)
                .storeUint(msg.outcome, 8)
                .storeAddress(contract.getAddress())
                .endCell();
            // W3④（D5 收紧）：二签方须为**非受益方**——RELEASE（outcome=0，钱付卖方）须买方签；
            // REFUND（outcome=1，钱退买方）须卖方签。仅"任一方可签任意方向"时，联邦+得利方
            // 合谋无法被挡（得利方当然乐意签）；收紧后跨向背书直接拒。
            assert (msg.isBuyer == (msg.outcome == 0)) throw Errors.BadPartySignature;
            val header: uint256? = msg.isBuyer ? extra.buyerPub : extra.sellerPub;
            // 未配置公钥：显式拒绝（BadPartySignature）——不把 null 展开成 type-check 崩溃
            assert (header != null) throw Errors.BadPartySignature;
            assert (isSignatureValid(payload.hash(), msg.sig as slice, header!!)) throw Errors.BadPartySignature;
            extra.resolveProposal = ResolveProposal {
                outcome: msg.outcome,
                proposedAt: blockchain.now(),
            }.toCell();
            storage.extra = extra.toCell();
            storage.save();
        }
        ExecuteResolveMessage => {
            // 冷静期满后执行（任何人可代发——执行的是"已公示的方向"，代发者改不了它）。
            var storage = lazy EscrowStorage.load();
            assert (storage.state == 3) throw Errors.InvalidState;
            var extra = loadExtraCompat(storage.extra);
            assert (extra.resolveProposal != null) throw Errors.NoResolveProposal;
            val proposal = extra.resolveProposal!!.load();
            assert (blockchain.now() >= proposal.proposedAt + RESOLVE_COOL_DOWN_SECS)
                throw Errors.ResolveNotReady;
            // 先落状态、后出款（与 Confirm/Refund 同序：回弹时 onBouncedMessage 按 state 4/5 还原）
            var payTo = storage.seller;
            var backTo = storage.buyer;
            if (proposal.outcome == 1) {
                payTo = storage.buyer;
                backTo = storage.seller;
                storage.state = 5;
            } else {
                storage.state = 4;
            }
            // W3.9③：提案**保留**（不清）——RELEASE 出账回弹后（state 回 2）经 Dispute 重入争议（2→3）
            // 即可直接重试 Execute，不必重新二签+再等 24h；**REFUND 出账回弹则回 1**（见 onBouncedMessage，
            // 两条回退路径不同）。常态成功后残留提案无害（state 非 3 时各路径均拒），联邦可 CancelResolve 清理。
            storage.save();
            // RELEASE（outcome=0）收平台费；REFUND 不收（退款不扣）
            payOut(storage, payTo, backTo, proposal.outcome == 0);
        }
        CancelResolveMessage => {
            // 撤销裁决提案（仅联邦）——撤后可重新提案；撤本身即链上留痕（消息记录在案）。
            var storage = lazy EscrowStorage.load();
            assert (in.senderAddress == storage.federation) throw Errors.NotFederation;
            var extra = loadExtraCompat(storage.extra);
            assert (extra.resolveProposal != null) throw Errors.NoResolveProposal;
            extra.resolveProposal = null;
            storage.extra = extra.toCell();
            storage.save();
        }
        EscapeVoteMessage => {
            // 升级逃生票（加固档，2026-10-04）：升级提案存续期间，买卖双方各一票；
            // 两票齐 → 立即全额退款（钱回买方）——即使联邦被入侵并提案恶意代码，
            // 双方永远有"把在托管中的钱撤出来"的窗口。仅钱还在合约（state 1/2/3）时可投。
            var storage = lazy EscrowStorage.load();
            assert (in.senderAddress == storage.buyer
                || in.senderAddress == storage.seller) throw Errors.NotParty;
            assert (storage.state == 1 || storage.state == 2 || storage.state == 3)
                throw Errors.InvalidState;
            var extra = loadExtraCompat(storage.extra);
            // W3②：放宽——升级提案存续**或任何暂停期间**均可投（防纯 Pause 单签冻死资金；仍须双方两票才放行）
            assert (extra.upgrade != null || extra.paused) throw Errors.NoUpgradeToEscape;
            if (in.senderAddress == storage.buyer) {
                extra.escapeBuyerVote = true;
            } else {
                extra.escapeSellerVote = true;
            }
            if (extra.escapeBuyerVote && extra.escapeSellerVote) {
                // 两票齐：立即退款给买方（先落状态、后出款——同 Refund 出账路径）
                storage.state = 5;
                storage.extra = extra.toCell();
                storage.save();
                // 逃生退款不扣费（chargeFee=false）
                payOut(storage, storage.buyer, storage.seller, false);
            } else {
                storage.extra = extra.toCell();
                storage.save();
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
            //   W3③：fee 启用时出账为两笔（net+fee 各一份燃料），门槛翻倍——否则第二笔可能失败
            val feeOn = loadExtraCompat(storage.extra).feeConfig != null;
            val minTon = feeOn ? grams("0.10") : grams("0.05");
            assert (in.valueCoins >= minTon) throw Errors.InsufficientFeeTon;
            //   ⑥ 仅未入金状态可被通知锁定（2026-10-05 深度审计修复）：对照 Fund 分支的 state==0——
            //      此前无守卫，终态（4/5）被合格通知写回 1 后可二次出账（探针 6/6 复现；
            //      用例 `jetton notification rejected outside state 0` 钉住）。
            assert (storage.state == 0) throw Errors.InvalidState;
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
            // 提案即自动冻结（2026-10-04 加固档；2026-10-05 审计 H6a 改派生式）：冻结由
            // 「upgrade != null」派生（见 onInternalMessage 的收报暂停前置检查）——不再写实体 paused，使 Cancel/Apply
            // 清 upgrade 即对称解冻，且不误清手动 Pause。审计窗口期内双方可投逃生票撤资。
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
            // W3⑥：state==0（未入金）允许联邦更正（写错即死单的逃生口）；入金后仍铁锁（信任锚不可掉包）
            assert (isZeroAddress(extra.ownJettonWallet) || storage.state == 0)
                throw Errors.JettonWalletAlreadySet;
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
            // W3⑤：上限 64 条（不同哈希）；同哈希重复提交幂等（覆盖）不占额。
            // 无上限时海量哈希可慢性耗光合约余额（存储费），出账将因余额不足而失败。
            if (!extra.evidence.exists(msg.evidenceHash)) {
                assert (evidenceCountOf(extra) < 64) throw Errors.InvalidMessage;
            }
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

/// 出账（TON 或 jetton 双路径；Confirm/ExecuteResolve/EscapeVote 共用）。
/// 调用方须先落状态并 save（本函数只负责发消息——出账回弹由 onBouncedMessage 还原状态）。
///
/// <h2>链上扣费（方案 B，2026-10-04）</h2>
/// chargeFee=true 且 extra.feeAddress / extra.feeRateBps 都配齐、bps>0 时：
/// fee = amount × bps / 10000（整数向下取整）；收款方得 amount−fee、费地址得 fee。
/// 否则行为与历史（全额出账）完全一致——**fee=0 是兼容护栏**。
/// 退款类出口（Refund / REFUND 裁决 / EscapeVote）传 chargeFee=false——退款不该再被扣。
fun payOut(storage: EscrowStorage, payTo: address, backTo: address, chargeFee: bool) {
    val extra = loadExtraCompat(storage.extra);
    var fee: coins = 0;
    var feeAddress: address? = null;
    if (chargeFee && extra.feeConfig != null) {
        val cfg = extra.feeConfig!!.load();
        if (cfg.feeRateBps > 0) {
            // W3①：费率上限守卫——保证 bounce 判据可分：fee ≤ amount/5（bps≤2000）时
            // net ≥ 0.8·amount，与 fee 相距 4 倍以上，onBouncedMessage 才能凭退回金额判定主笔/费笔。
            assert (cfg.feeRateBps <= 2000) throw Errors.InvalidMessage;
            fee = storage.amount * cfg.feeRateBps / 10000;
            // 护栏：bps 配置失误（≥10000）不得让收款方颗粒无收——拒绝而非静默扣光
            assert (fee < storage.amount) throw Errors.InvalidMessage;
            feeAddress = cfg.feeAddress;
        }
    }
    val net: coins = storage.amount - fee;
    if (extra.asset == 1) {
        // 净额给收款方（发给**我方 jetton 钱包**，报文指定收款方）
        // W3①：queryId=1 标记主笔（回弹时凭原 body 的 queryId 判定）；bounce 改 Only256 形态
        // W3.9④ 已知边界：destination 为「未部署 jetton 钱包的新用户」时，对方钱包的部署成本从本消息
        // 附带的 value（0.05）支出——理论可能不足（真机未见失败样本；B19「全新用户首收 USDT」为核实项）。
        // 处方（若真机红）：提升出账附带值（0.05→0.07）并重编译重升级。forwardTonAmount=0 不相关且正确
        // （该字段只控制「是否给对方发 transfer_notification 回执」，与对方钱包部署无关）。
        createMessage({
            bounce: BounceMode.Only256BitsOfBody,
            value: grams("0.05"),
            dest: extra.ownJettonWallet,
            body: JettonTransfer {
                queryId: 1,
                amount: net,
                destination: payTo,
                responseDestination: backTo,
                customPayload: null,
                forwardTonAmount: 0,
                forwardPayload: TlbEither<cell, Cell<cell>>.right(beginCell().endCell().toCell()),
            },
        }).send(SEND_MODE_PAY_FEES_SEPARATELY | SEND_MODE_BOUNCE_ON_ACTION_FAIL);
        if (fee > 0) {
            // 费（jetton）：第二笔 transfer——同钱包、不同收款方（费地址）；queryId=2 标记费笔
            createMessage({
                bounce: BounceMode.Only256BitsOfBody,
                value: grams("0.05"),
                dest: extra.ownJettonWallet,
                body: JettonTransfer {
                    queryId: 2,
                    amount: fee,
                    destination: feeAddress!!,
                    responseDestination: backTo,
                    customPayload: null,
                    forwardTonAmount: 0,
                    forwardPayload: TlbEither<cell, Cell<cell>>.right(beginCell().endCell().toCell()),
                },
            }).send(SEND_MODE_PAY_FEES_SEPARATELY | SEND_MODE_BOUNCE_ON_ACTION_FAIL);
        }
    } else {
        // W3①：bounce 改 Only256BitsOfBody（gas 更低；判据在 onBouncedMessage：退回金额阈值）
        createMessage({
            bounce: BounceMode.Only256BitsOfBody,
            value: net,
            dest: payTo,
        }).send(SEND_MODE_PAY_FEES_SEPARATELY | SEND_MODE_BOUNCE_ON_ACTION_FAIL);
        if (fee > 0) {
            createMessage({
                bounce: BounceMode.Only256BitsOfBody,
                value: fee,
                dest: feeAddress!!,
            }).send(SEND_MODE_PAY_FEES_SEPARATELY | SEND_MODE_BOUNCE_ON_ACTION_FAIL);
        }
    }
}

/// 零地址哨兵（workchain 0 + 256-bit 全零）：部署时 `ownJettonWallet` 的初始值。
/// 两阶段 W 设置（SetJettonWallet）以它为"未设置"的判据——见 types.tolk 的说明。
fun isZeroAddress(a: address): bool {
    return a == address("0:0000000000000000000000000000000000000000000000000000000000000000");
}

/// 证据条数（W3⑤ 写入上限守卫与 get evidenceCount 共用）——map 无 size，遍历计数。
fun evidenceCountOf(extra: EscrowExtra): int {
    var m = extra.evidence;
    var count = 0;
    var r = m.findFirst();
    while (r.isFound) {
        count += 1;
        r = m.iterateNext(r);
    }
    return count;
}

fun onBouncedMessage(in: InMessageBounced) {
    // 出站资金被回弹（目标不可达/拒收）：把状态退回去，避免出现「账上已放款、钱却没出去」。
    // W3① 加固：fee 启用时出账为两笔（net+fee）——只有**主笔（net）**回弹才回退状态；
    // 费笔回弹不扰动状态 + 退回值补偿给卖方（费没收成不滞留——合约无提款接口，“链下补收”不可行，见下）。旧实现「任何回弹都回退」
    // 在多笔出账下会：费笔回弹 → 状态回 2 → 重试 Confirm → 重复出账（外部评审 P0①）。
    var storage = lazy EscrowStorage.load();
    if (storage.state == 4) {
        // 内置消息结构的字段按值取出传递（InMessageBounced 不可整体作为对象传递）
        if (isMainPayoutBounce(in.bouncedBody, in.valueCoins, storage)) {
            storage.state = 2;   // 主笔未达 → 可重试放款
            storage.save();
        } else {
            // W3.9②：费笔回弹——状态不动 + 退回值转给卖方（费从卖方净额中扣出、没送成即退还被扣者；
            // 费不再滞留合约——合约无提款接口，"链下补收"不可行）。
            // NoBounce：该补偿笔若再失败即销毁（金额 ≤2%·amount），不再回弹——防「回弹→再补偿」循环烧 gas。
            createMessage({
                bounce: BounceMode.NoBounce,
                value: in.valueCoins,
                dest: storage.seller,
            }).send(SEND_MODE_PAY_FEES_SEPARATELY);
        }
    } else if (storage.state == 5) {
        // 退款类出口恒为单笔（payOut chargeFee=false）——任何回弹即主笔
        storage.state = 1;   // REFUNDED → LOCKED：可重试退款
        storage.save();
    }
}

/// 主笔判定（W3①，出账两笔的回弹分轨）：
///  · TON 单（asset=0）：转账无 body，按**退回金额**判定——退回值 > amount/2 即主笔。
///    前提由 payOut 的费率守卫保证（bps≤2000 → net ≥ 0.8·amount、fee ≤ 0.2·amount，相距 4 倍以上）。
///  · jetton 单（asset=1）：解析 bounce 原 body（Only256BitsOfBody 形态：0xFFFFFFFF 前缀 +
///    原 body 前 256 位）——读 JettonTransfer 的 op+queryId，net=1 / fee=2。
fun isMainPayoutBounce(bouncedBody: slice, valueCoins: coins, storage: EscrowStorage): bool {
    val extra = loadExtraCompat(storage.extra);
    if (extra.asset == 1) {
        var body = bouncedBody;
        body.skipBouncedPrefix();
        if (body.remainingBitsCount() < 96) {
            // 形态不符（异常载荷）：保守视为主笔回退——宁可回退可重试，不吞掉出账失败
            return true;
        }
        val op = body.loadUint(32);
        val queryId = body.loadUint(64);
        return op == 0x0f8a7ea5 && queryId == 1;
    }
    return valueCoins > storage.amount / 2;
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
    return evidenceCountOf(loadExtraCompat(storage.extra));
}
