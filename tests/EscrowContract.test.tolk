// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 telegram-escrow-bot contributors
//
// EscrowContract 非裁决路径的行为测试（Wave 3 增量 1：受守卫的状态机）。
// 目的：把「谁能迁到哪一步」钉死——尤其是**乱序消息不得越级**（ET-58）。
// 资金划转与 Jetton 验证属增量 2，本文件只测状态与守卫。

import "@acton/emulation/network"
import "@acton/types/message"
import "@acton/emulation/testing"
import "@acton/io"
import "@acton/build"
import "@acton/testing/expect"

import "@contracts/types"
import "@wrappers/EscrowContract.gen"

// 状态：0=OPEN 1=LOCKED 2=DELIVERED 3=DISPUTED 4=RELEASED 5=REFUNDED

fun baseStorage(buyer: address, seller: address, federation: address, state: int): EscrowStorage {
    return {
        buyer: buyer,
        seller: seller,
        federation: federation,
        amount: grams("1"),
        state: state,
        extra: EscrowExtra { ownJettonWallet: buyer, asset: 0, upgrade: null, evidence: [], paused: false }.toCell(),   // TON 计价（占位地址）
    };
}

fun deployWithState(buyer: Treasury, seller: Treasury, federation: Treasury,
                    state: int): EscrowContract {
    val contract = EscrowContract.fromStorage(baseStorage(buyer.address, seller.address,
        federation.address, state));
    expect(contract.deploy(buyer.address, { value: grams("2") })).toHaveSuccessfulDeploy({
        to: contract.address,
    });
    return contract;
}

fun setupLocked(): (EscrowContract, Treasury, Treasury, Treasury) {
    val buyer = testing.treasury("buyer");
    val seller = testing.treasury("seller");
    val federation = testing.treasury("federation");

    return (deployWithState(buyer, seller, federation, 1), buyer, seller, federation);
}

get fun `test deploy exposes initial state and parties`() {
    val (contract, buyer, seller, _) = setupLocked();

    expect(contract.escrowState()).toEqual(1);
    expect(contract.buyer()).toEqual(buyer.address);
    expect(contract.seller()).toEqual(seller.address);
}

get fun `test seller marks delivered from LOCKED`() {
    val (contract, _, seller, _) = setupLocked();

    val res = contract.sendDeliverMessage(seller.address);
    expect(res).toHaveSuccessfulTx<DeliverMessage>({
        from: seller.address,
        to: contract.address,
    });

    expect(contract.escrowState()).toEqual(2);
}

get fun `test buyer cannot mark delivered`() {
    val (contract, buyer, _, _) = setupLocked();

    val res = contract.sendDeliverMessage(buyer.address);
    expect(res).toHaveFailedTx({
        from: buyer.address,
        to: contract.address,
        exitCode: Errors.NotSeller,
    });

    expect(contract.escrowState()).toEqual(1);
}

get fun `test cannot deliver before locked`() {
    val buyer = testing.treasury("buyer");
    val seller = testing.treasury("seller");
    val federation = testing.treasury("federation");
    val contract = deployWithState(buyer, seller, federation, 0);   // OPEN：资金未托管

    // 乱序消息不得越级迁移（ET-58）
    val res = contract.sendDeliverMessage(seller.address);
    expect(res).toHaveFailedTx({
        from: seller.address,
        to: contract.address,
        exitCode: Errors.InvalidState,
    });

    expect(contract.escrowState()).toEqual(0);
}

get fun `test buyer confirms from DELIVERED`() {
    val buyer = testing.treasury("buyer");
    val seller = testing.treasury("seller");
    val federation = testing.treasury("federation");
    val contract = deployWithState(buyer, seller, federation, 2);   // DELIVERED

    val res = contract.sendConfirmMessage(buyer.address);
    expect(res).toHaveSuccessfulTx<ConfirmMessage>({
        from: buyer.address,
        to: contract.address,
    });

    expect(contract.escrowState()).toEqual(4);
}

get fun `test seller cannot confirm`() {
    val buyer = testing.treasury("buyer");
    val seller = testing.treasury("seller");
    val federation = testing.treasury("federation");
    val contract = deployWithState(buyer, seller, federation, 2);

    val res = contract.sendConfirmMessage(seller.address);
    expect(res).toHaveFailedTx({
        from: seller.address,
        to: contract.address,
        exitCode: Errors.NotBuyer,
    });

    expect(contract.escrowState()).toEqual(2);
}

get fun `test refund allowed from LOCKED`() {
    val (contract, buyer, _, _) = setupLocked();

    val res = contract.sendRefundMessage(buyer.address);
    expect(res).toHaveSuccessfulTx<RefundMessage>({
        from: buyer.address,
        to: contract.address,
    });

    expect(contract.escrowState()).toEqual(5);
}

get fun `test refund rejected after release`() {
    val buyer = testing.treasury("buyer");
    val seller = testing.treasury("seller");
    val federation = testing.treasury("federation");
    val contract = deployWithState(buyer, seller, federation, 4);   // 已 RELEASED

    val res = contract.sendRefundMessage(buyer.address);
    expect(res).toHaveFailedTx({
        from: buyer.address,
        to: contract.address,
        exitCode: Errors.InvalidState,
    });
}

// ── 增量 3：Jetton 发送者校验（ET-50）────────────────────────────────────
// TEP-74（标准原文第 82/95 行）：transfer_notification 由**收款方自己的 jetton 钱包**发出，
// 报文里的 `sender` 是**上一持有人**（买方）——两个地址不同，两处都要验。
// 用标准报文 + 普通地址即可测，无需在测试里部署整套 jetton 合约。

fun deployWithJetton(buyer: Treasury, seller: Treasury, federation: Treasury,
                     ownJettonWallet: address, state: int): EscrowContract {
    val contract = EscrowContract.fromStorage({
        buyer: buyer.address,
        seller: seller.address,
        federation: federation.address,
        amount: grams("1"),
        state: state,
        extra: EscrowExtra { ownJettonWallet: ownJettonWallet, asset: 1, upgrade: null, evidence: [], paused: false }.toCell(),   // JETTON 计价
    });
    expect(contract.deploy(buyer.address, { value: grams("2") })).toHaveSuccessfulDeploy({
        to: contract.address,
    });
    return contract;
}

fun jettonNotificationBody(previousOwner: address): cell {
    return jettonNotificationBodyWithAmount(previousOwner, grams("1"));
}

fun jettonNotificationBodyWithAmount(previousOwner: address, amount: coins): cell {
    return beginCell()
        .storeInt(0x7362d09c, 32)      // 标准 op：transfer_notification
        .storeInt(0, 64)               // queryId
        .storeCoins(amount)            // amount（jetton 数量）
        .storeAddress(previousOwner)   // sender = 上一持有人
        .endCell();
}

get fun `test forged jetton notification is rejected`() {
    val buyer = testing.treasury("buyer");
    val seller = testing.treasury("seller");
    val federation = testing.treasury("federation");
    val own = testing.treasury("ownWallet");
    val attacker = testing.treasury("attackerWallet");
    val contract = deployWithJetton(buyer, seller, federation, own.address, 0);   // OPEN

    // 攻击者**直接**发通知，并谎称上一持有人是买方（报文可伪造，实际发送者不能）
    val res = contract.sendAny(attacker.address, jettonNotificationBody(buyer.address));
    expect(res).toHaveFailedTx({
        from: attacker.address,
        to: contract.address,
        exitCode: Errors.NotTrustedWallet,
    });

    expect(contract.escrowState()).toEqual(0);
}

get fun `test jetton notification claiming wrong previous owner is rejected`() {
    val buyer = testing.treasury("buyer");
    val seller = testing.treasury("seller");
    val federation = testing.treasury("federation");
    val own = testing.treasury("ownWallet");
    val third = testing.treasury("thirdParty");
    val contract = deployWithJetton(buyer, seller, federation, own.address, 0);

    // 发送者**确实**是我方钱包，但报文声明的上一持有人不是买方 —— 仍在拒绝之列
    val res = contract.sendAny(own.address, jettonNotificationBody(third.address));
    expect(res).toHaveFailedTx({
        from: own.address,
        to: contract.address,
        exitCode: Errors.NotBuyer,
    });

    expect(contract.escrowState()).toEqual(0);
}

// ── 增量 4：jetton 记账（asset）与 jetton 出账 ───────────────────────────
// 目标：让 jetton 计价的托管**真正可用**——入金按通知金额记账、出账把 jetton 划给收款方。
// 这两件必须一起做：只做入金会落到"收下了却付不出"的半状态（上一轮已因此显式拒绝）。

get fun `test ton escrow rejects jetton notification`() {
    val buyer = testing.treasury("buyer");
    val seller = testing.treasury("seller");
    val federation = testing.treasury("federation");
    val own = testing.treasury("ownWallet");
    val contract = deployWithState(buyer, seller, federation, 0);   // asset = TON

    // TON 计价的托管收到真实 jetton 通知：资产不符，必须拒（否则等于用 jetton 冒充 TON 入金）
    // 注意：发送方须用该托管**已登记的**我方钱包（此处占位为 buyer），否则先命中的是发送者守卫
    val res = contract.sendAny(buyer.address, jettonNotificationBody(buyer.address));
    expect(res).toHaveFailedTx({
        from: buyer.address,
        to: contract.address,
        exitCode: Errors.WrongAsset,
    });

    expect(contract.escrowState()).toEqual(0);
}

get fun `test jetton escrow locks on genuine notification`() {
    val buyer = testing.treasury("buyer");
    val seller = testing.treasury("seller");
    val federation = testing.treasury("federation");
    val own = testing.treasury("ownWallet");
    val contract = deployWithJetton(buyer, seller, federation, own.address, 0);   // asset = JETTON

    // 出账已实现，故真实通知应当**入金成功**（不再拒绝）——这是本轮的行为变更
    val res = contract.sendAny(own.address, jettonNotificationBody(buyer.address),
        { value: grams("0.2") });   // 附足额 TON 作日后划转的燃料
    expect(res).toHaveSuccessfulTx({
        from: own.address,
        to: contract.address,
    });

    expect(contract.escrowState()).toEqual(1);   // LOCKED
}

get fun `test jetton payout goes to own wallet with standard transfer`() {
    val buyer = testing.treasury("buyer");
    val seller = testing.treasury("seller");
    val federation = testing.treasury("federation");
    val own = testing.treasury("ownWallet");
    val contract = deployWithJetton(buyer, seller, federation, own.address, 0);   // asset = JETTON

    // 入金：我方钱包发来真实通知（附足额 TON 作后续划转的燃料）
    expect(contract.sendAny(own.address, jettonNotificationBody(buyer.address),
        { value: grams("0.2") })).toHaveSuccessfulTx({ from: own.address, to: contract.address });
    expect(contract.escrowState()).toEqual(1);   // LOCKED

    expect(contract.sendAny(seller.address, beginCell().storeInt(0x7d5a1002, 32).endCell()))
        .toHaveSuccessfulTx({ from: seller.address, to: contract.address });    // DELIVERED（仅卖方）

    // 买方确认 → 出账**不是** TON 转账，而是发往「我方 jetton 钱包」的标准 transfer（TEP-74）
    val res = contract.sendAny(buyer.address, beginCell().storeInt(0x7d5a1003, 32).endCell());
    expect(res).toHaveSuccessfulTx({ from: buyer.address, to: contract.address });

    val outs = res.at(0).outMessages;
    expect(outs.size()).toEqual(1);
    val out = outs.at<JettonTransfer>(0);
    val body = out.loadBody();
    expect(out.info.dest).toEqual(own.address);              // 发给**我方**钱包，不是收款方本人
    expect(body.destination).toEqual(seller.address);        // 报文里指定收款方
    expect(body.amount).toEqual(grams("1"));                 // 划转=托管额
    expect(contract.escrowState()).toEqual(4);               // RELEASED
}

get fun `test jetton refund sends transfer to own wallet for buyer`() {
    val buyer = testing.treasury("buyer");
    val seller = testing.treasury("seller");
    val federation = testing.treasury("federation");
    val own = testing.treasury("ownWallet");
    val contract = deployWithJetton(buyer, seller, federation, own.address, 0);   // asset = JETTON

    expect(contract.sendAny(own.address, jettonNotificationBody(buyer.address),
        { value: grams("0.2") })).toHaveSuccessfulTx({ from: own.address, to: contract.address });
    expect(contract.escrowState()).toEqual(1);   // LOCKED

    // 退款与放款共用同一条出账路径，但收款方不同、终态不同——必须单独钉住
    val res = contract.sendAny(seller.address, beginCell().storeInt(0x7d5a1004, 32).endCell());
    expect(res).toHaveSuccessfulTx({ from: seller.address, to: contract.address });

    val outs = res.at(0).outMessages;
    expect(outs.size()).toEqual(1);
    val body = outs.at<JettonTransfer>(0).loadBody();
    expect(outs.at<JettonTransfer>(0).info.dest).toEqual(own.address);   // 仍发给我方钱包
    expect(body.destination).toEqual(buyer.address);                    // 报文里是买方
    expect(body.amount).toEqual(grams("1"));
    expect(contract.escrowState()).toEqual(5);                          // REFUNDED
}

get fun `test jetton escrow rejects short notification`() {
    val buyer = testing.treasury("buyer");
    val seller = testing.treasury("seller");
    val federation = testing.treasury("federation");
    val own = testing.treasury("ownWallet");
    val contract = deployWithJetton(buyer, seller, federation, own.address, 0);

    // 同资产下的量纲一致，短付可比较、必须拒
    val res = contract.sendAny(own.address, jettonNotificationBodyWithAmount(buyer.address,
        grams("0.5")));
    expect(res).toHaveFailedTx({
        from: own.address,
        to: contract.address,
        exitCode: Errors.InsufficientFunds,
    });

    expect(contract.escrowState()).toEqual(0);
}

get fun `test unknown message is rejected`() {
    val (contract, _, seller, _) = setupLocked();

    val res = contract.sendAny(seller.address, beginCell().storeInt(0x999, 32).endCell());
    expect(res).toHaveFailedTx({
        from: seller.address,
        to: contract.address,
        exitCode: Errors.InvalidMessage,
    });
}

// ── 增量 2：资金路径 ─────────────────────────────────────────────────────

get fun `test fund rejects short payment`() {
    val buyer = testing.treasury("buyer");
    val seller = testing.treasury("seller");
    val federation = testing.treasury("federation");
    val contract = deployWithState(buyer, seller, federation, 0);   // OPEN

    // 只到账一部分就进 LOCKED 会让"已锁定"名不副实
    val short = contract.sendFundMessage(buyer.address, { value: grams("0.5") });
    expect(short).toHaveFailedTx({
        from: buyer.address,
        to: contract.address,
        exitCode: Errors.InsufficientFunds,
    });

    expect(contract.escrowState()).toEqual(0);
}

get fun `test fund locks when full amount arrives`() {
    val buyer = testing.treasury("buyer");
    val seller = testing.treasury("seller");
    val federation = testing.treasury("federation");
    val contract = deployWithState(buyer, seller, federation, 0);

    val ok = contract.sendFundMessage(buyer.address, { value: grams("1.5") });
    expect(ok).toHaveSuccessfulTx<FundMessage>({
        from: buyer.address,
        to: contract.address,
    });

    expect(contract.escrowState()).toEqual(1);
}

get fun `test only buyer can fund`() {
    val buyer = testing.treasury("buyer");
    val seller = testing.treasury("seller");
    val federation = testing.treasury("federation");
    val contract = deployWithState(buyer, seller, federation, 0);

    val res = contract.sendFundMessage(seller.address, { value: grams("1.5") });
    expect(res).toHaveFailedTx({
        from: seller.address,
        to: contract.address,
        exitCode: Errors.NotBuyer,
    });

    expect(contract.escrowState()).toEqual(0);
}

get fun `test confirm pays seller exactly the escrowed amount`() {
    val buyer = testing.treasury("buyer");
    val seller = testing.treasury("seller");
    val federation = testing.treasury("federation");
    val contract = deployWithState(buyer, seller, federation, 2);   // DELIVERED
    val before = testing.getAccountBalance(seller.address);

    val res = contract.sendConfirmMessage(buyer.address);
    expect(res).toHaveSuccessfulTx<ConfirmMessage>({
        from: buyer.address,
        to: contract.address,
    });

    expect(contract.escrowState()).toEqual(4);
    // 实收 = 托管金额 − 转发费（探针实测 20601 nano，随消息大小浮动）
    // ——锁死单点会漂移，故用带宽断言，而不是「正好等于」
    val got = testing.getAccountBalance(seller.address) - before;
    assert (got > grams("0.999") && got < grams("1")) throw 102;
}

get fun `test fund returns the surplus to the buyer`() {
    val buyer = testing.treasury("buyer");
    val seller = testing.treasury("seller");
    val federation = testing.treasury("federation");
    val contract = deployWithState(buyer, seller, federation, 0);   // OPEN
    val before = testing.getAccountBalance(contract.address);

    val res = contract.sendFundMessage(buyer.address, { value: grams("1.5") });
    expect(res).toHaveSuccessfulTx<FundMessage>({
        from: buyer.address,
        to: contract.address,
    });

    expect(contract.escrowState()).toEqual(1);
    // 合约只该净增「订单金额」量级（1 TON）；多付的 0.5 必须退回，
    // 否则买方的钱被永久吞没（本合约无提款接口）
    val delta = testing.getAccountBalance(contract.address) - before;
    assert (delta < grams("1.1")) throw 105;
}

get fun `test bounced payout reverts RELEASED to DELIVERED`() {
    val buyer = testing.treasury("buyer");
    val seller = testing.treasury("seller");
    val federation = testing.treasury("federation");
    val contract = deployWithState(buyer, seller, federation, 4);   // 已 RELEASED

    // 模拟出账被回弹（目标不可达/拒收）——这是「钱没走成」的唯一安全网
    val bounced = createMessage({
        bounce: false,
        value: grams("0.1"),
        dest: contract.address,
    }).bounced();
    testing.processSingleTraceStep(buyer.address, bounced);

    // 回弹后状态必须退回，否则会出现「账上已放款、钱却没出去」
    expect(contract.escrowState()).toEqual(2);
}

get fun `test bounced refund reverts REFUNDED to LOCKED`() {
    val buyer = testing.treasury("buyer");
    val seller = testing.treasury("seller");
    val federation = testing.treasury("federation");
    val contract = deployWithState(buyer, seller, federation, 5);   // 已 REFUNDED

    val bounced = createMessage({
        bounce: false,
        value: grams("0.1"),
        dest: contract.address,
    }).bounced();
    testing.processSingleTraceStep(buyer.address, bounced);

    expect(contract.escrowState()).toEqual(1);
}

get fun `test bounce with no payout in flight leaves state alone`() {
    val (contract, buyer, _, _) = setupLocked();   // LOCKED：没有出账在途

    val bounced = createMessage({
        bounce: false,
        value: grams("0.1"),
        dest: contract.address,
    }).bounced();
    testing.processSingleTraceStep(buyer.address, bounced);

    // 无出账在途时的回弹不该改动状态（避免误退）
    expect(contract.escrowState()).toEqual(1);
}

get fun `test refund pays buyer exactly the escrowed amount`() {
    val buyer = testing.treasury("buyer");
    val seller = testing.treasury("seller");
    val federation = testing.treasury("federation");
    val contract = deployWithState(buyer, seller, federation, 1);   // LOCKED
    val before = testing.getAccountBalance(buyer.address);

    val res = contract.sendRefundMessage(buyer.address);
    expect(res).toHaveSuccessfulTx<RefundMessage>({
        from: buyer.address,
        to: contract.address,
    });

    expect(contract.escrowState()).toEqual(5);
    val got = testing.getAccountBalance(buyer.address) - before;
    assert (got > grams("0.999") && got < grams("1")) throw 103;
}

get fun `test deliver does not pay anyone`() {
    val (contract, _, seller, _) = setupLocked();
    val before = testing.getAccountBalance(contract.address);

    val res = contract.sendDeliverMessage(seller.address);
    expect(res).toHaveSuccessfulTx<DeliverMessage>({
        from: seller.address,
        to: contract.address,
    });

    expect(contract.escrowState()).toEqual(2);
    // 曾用「合约余额不变」断言「不碰钱」——**前提错了**：测试侧 SendParams 的默认
    // value 是 0.1 TON，任何入站消息都会让它增加。改为断言「没有因出账而减少」：
    // deliver 分支没有任何 send 调用，入站 value 只会让余额上升。
    assert (testing.getAccountBalance(contract.address) > before) throw 104;
}

// ── 临时探针已在诊断后清理（结论见上面两处断言注释与提交信息）──

// ── S5 争议裁决（Wave 1 · 方案 A：链下多签 + 联邦单方上链执行）──
// Dispute：当事任一方、state ∈ {1,2} → 3；Resolve：仅联邦、state=3、按 outcome 出款（0=RELEASE 放款卖方 → 态 4；1=REFUND 退款买方 → 态 5）。

fun setupDisputed(): (EscrowContract, Treasury, Treasury, Treasury) {
    val buyer = testing.treasury("buyer");
    val seller = testing.treasury("seller");
    val federation = testing.treasury("federation");
    return (deployWithState(buyer, seller, federation, 3), buyer, seller, federation);
}

get fun `test dispute from LOCKED goes to DISPUTED`() {
    val (contract, buyer, _, _) = setupLocked();

    val res = contract.sendDisputeMessage(buyer.address);
    expect(res).toHaveSuccessfulTx<DisputeMessage>({
        from: buyer.address,
        to: contract.address,
    });

    expect(contract.escrowState()).toEqual(3);
}

get fun `test dispute from DELIVERED goes to DISPUTED`() {
    val buyer = testing.treasury("buyer");
    val seller = testing.treasury("seller");
    val federation = testing.treasury("federation");
    val contract = deployWithState(buyer, seller, federation, 2);   // DELIVERED

    val res = contract.sendDisputeMessage(seller.address);
    expect(res).toHaveSuccessfulTx<DisputeMessage>({
        from: seller.address,
        to: contract.address,
    });

    expect(contract.escrowState()).toEqual(3);
}

get fun `test resolve release pays seller and ends RELEASED`() {
    val (contract, _, seller, federation) = setupDisputed();

    val res = contract.sendResolveMessage(federation.address, 0);   // 0 = RELEASE
    expect(res).toHaveSuccessfulTx<ResolveMessage>({
        from: federation.address,
        to: contract.address,
    });

    expect(contract.escrowState()).toEqual(4);
}

get fun `test resolve refund pays buyer and ends REFUNDED`() {
    val (contract, buyer, _, federation) = setupDisputed();

    val res = contract.sendResolveMessage(federation.address, 1);   // 1 = REFUND
    expect(res).toHaveSuccessfulTx<ResolveMessage>({
        from: federation.address,
        to: contract.address,
    });

    expect(contract.escrowState()).toEqual(5);
}

get fun `test dispute by non-party rejected`() {
    val (contract, _, _, _) = setupLocked();
    val stranger = testing.treasury("stranger");

    val res = contract.sendDisputeMessage(stranger.address);
    expect(res).toHaveFailedTx({
        from: stranger.address,
        to: contract.address,
        exitCode: Errors.NotParty,
    });

    expect(contract.escrowState()).toEqual(1);
}

get fun `test dispute from OPEN rejected`() {
    val buyer = testing.treasury("buyer");
    val seller = testing.treasury("seller");
    val federation = testing.treasury("federation");
    val contract = deployWithState(buyer, seller, federation, 0);   // OPEN（未入金）

    val res = contract.sendDisputeMessage(buyer.address);
    expect(res).toHaveFailedTx({
        from: buyer.address,
        to: contract.address,
        exitCode: Errors.InvalidState,
    });

    expect(contract.escrowState()).toEqual(0);
}

get fun `test resolve by non-federation rejected`() {
    val (contract, buyer, seller, _) = setupDisputed();

    val res = contract.sendResolveMessage(buyer.address, 0);
    expect(res).toHaveFailedTx({
        from: buyer.address,
        to: contract.address,
        exitCode: Errors.NotFederation,
    });

    val res2 = contract.sendResolveMessage(seller.address, 1);
    expect(res2).toHaveFailedTx({
        from: seller.address,
        to: contract.address,
        exitCode: Errors.NotFederation,
    });

    expect(contract.escrowState()).toEqual(3);
}

get fun `test resolve from non-DISPUTED state rejected`() {
    val (contract, _, _, federation) = setupLocked();   // state=1

    val res = contract.sendResolveMessage(federation.address, 0);
    expect(res).toHaveFailedTx({
        from: federation.address,
        to: contract.address,
        exitCode: Errors.InvalidState,
    });

    expect(contract.escrowState()).toEqual(1);
}

get fun `test resolve with invalid outcome rejected`() {
    val (contract, _, _, federation) = setupDisputed();

    val res = contract.sendResolveMessage(federation.address, 2);   // 2 = 非法
    expect(res).toHaveFailedTx({
        from: federation.address,
        to: contract.address,
        exitCode: Errors.InvalidMessage,
    });

    expect(contract.escrowState()).toEqual(3);
}

// ── S6 合约升级入口：提案 → 冷静期 → 执行 的时间锁生命周期 ──────────────
// 语义来源：docs/UNDECIDABLE-DECISIONS.md ⑥（方案 a + c 兜底）与落盘计划
// 「合约升级入口-方案-a-c-兜底-提案-冷静期-执行的时间锁升级」。
//   · 授权 = storage.federation（与 Resolve 同信任根；多签在链下，A0-2 已拍板不做链上多签）。
//   · 时间锁**在合约内**校验：Apply 须满足 now ≥ proposedAt + 冷静期。
//   · 升级生命周期与交易状态机**正交**——Propose/Apply/Cancel 三分支都不检查 escrow state。
//   · 留痕走外部日志消息（UpgradeProposed/Applied/CancelledEvent），按事件类型断言。
//
// 前置依赖（先落 RED）：本组用例引用 types.tolk 的 Propose/Apply/Cancel 消息、三事件与
// UpgradePending/NoUpgradeProposal/UpgradeNotReady 错误码，EscrowExtra.upgrade 字段，
// 合约三分支实现、get fun upgradeStatus，以及 `acton wrapper` 再生成的三 send 方法。
// 在上述任一落地前，本文件无法编译（即 RED）——这正是 TDD 序（T3 用例先于 T4 实现）。

// 冷静期（秒）= 72h。合约内为常量；测试**按规格钉死该值**——若合约改动而不同步，用例即红
// （把「规格」变成断言，而不是照抄合约常量使断言恒真）。
const UPGRADE_COOL_DOWN_SECS = 259200;

// 升级后挂载的新代码：用合约**自身**的 code，升级后行为不变。
// 这一步是必需的——若 newCode 是一段非法代码，setCodePostponed 会让合约当场僵死，
// APPLY-3 正是钉住「升级后合约仍存活」这一点。
fun upgradeCode(): cell {
    return build("EscrowContract");
}

get fun `test PROP-1 federation propose records code and emits event`() {
    val buyer = testing.treasury("buyer");
    val seller = testing.treasury("seller");
    val federation = testing.treasury("federation");
    val contract = deployWithState(buyer, seller, federation, 1);   // LOCKED
    val newCode = upgradeCode();

    val proposeTime = testing.getNow();
    val res = contract.sendProposeUpgradeMessage(federation.address, newCode,
        { value: grams("0.2") });
    expect(res).toHaveSuccessfulTx({
        from: federation.address,
        to: contract.address,
    });
    expect(res).toEmitExternalMessage<UpgradeProposedEvent>();

    // upgradeStatus = (newCode.hash(), 提案时刻)——合约记录的正是所提代码的 hash
    val (codeHash, proposedAt) = contract.upgradeStatus();
    expect(codeHash).toEqual(newCode.hash());
    expect(proposedAt).toEqual(proposeTime);
}

get fun `test PROP-2 non federation propose is rejected`() {
    val buyer = testing.treasury("buyer");
    val seller = testing.treasury("seller");
    val federation = testing.treasury("federation");
    val contract = deployWithState(buyer, seller, federation, 1);

    // 买方（非联邦）不可提案——升级权只能由联邦发起
    val res = contract.sendProposeUpgradeMessage(buyer.address, upgradeCode(),
        { value: grams("0.2") });
    expect(res).toHaveFailedTx({
        from: buyer.address,
        to: contract.address,
        exitCode: Errors.NotFederation,
    });

    val (codeHash, proposedAt) = contract.upgradeStatus();
    expect(codeHash).toEqual(0);
    expect(proposedAt).toEqual(0);
}

get fun `test PROP-2 non federation apply and cancel are rejected`() {
    val buyer = testing.treasury("buyer");
    val seller = testing.treasury("seller");
    val federation = testing.treasury("federation");
    val contract = deployWithState(buyer, seller, federation, 1);
    expect(contract.sendProposeUpgradeMessage(federation.address, upgradeCode(),
        { value: grams("0.2") })).toHaveSuccessfulTx({
        from: federation.address,
        to: contract.address,
    });

    // 提案已存在，但 Apply/Cancel 同样只有联邦可发
    val apply = contract.sendApplyUpgradeMessage(buyer.address, { value: grams("0.2") });
    expect(apply).toHaveFailedTx({
        from: buyer.address,
        to: contract.address,
        exitCode: Errors.NotFederation,
    });

    val cancel = contract.sendCancelUpgradeMessage(seller.address, { value: grams("0.2") });
    expect(cancel).toHaveFailedTx({
        from: seller.address,
        to: contract.address,
        exitCode: Errors.NotFederation,
    });
}

get fun `test PROP-3 duplicate propose rejected, cancel then propose succeeds`() {
    val buyer = testing.treasury("buyer");
    val seller = testing.treasury("seller");
    val federation = testing.treasury("federation");
    val contract = deployWithState(buyer, seller, federation, 1);
    val code1 = upgradeCode();
    val code2 = beginCell().storeUint(0x7, 4).endCell();   // 与 code1 不同，用于验证确实换了 newCode

    expect(contract.sendProposeUpgradeMessage(federation.address, code1,
        { value: grams("0.2") })).toHaveSuccessfulTx({
        from: federation.address,
        to: contract.address,
    });

    // 已有提案时再 Propose：拒绝覆盖（UpgradePending）——天然防「计时器热替换」
    val dup = contract.sendProposeUpgradeMessage(federation.address, code2,
        { value: grams("0.2") });
    expect(dup).toHaveFailedTx({
        from: federation.address,
        to: contract.address,
        exitCode: Errors.UpgradePending,
    });

    expect(contract.sendCancelUpgradeMessage(federation.address,
        { value: grams("0.2") })).toHaveSuccessfulTx({
        from: federation.address,
        to: contract.address,
    });

    expect(contract.sendProposeUpgradeMessage(federation.address, code2,
        { value: grams("0.2") })).toHaveSuccessfulTx({
        from: federation.address,
        to: contract.address,
    });
    val (codeHash, _) = contract.upgradeStatus();
    expect(codeHash).toEqual(code2.hash());
}

get fun `test APPLY-1 apply before cool down is not ready and keeps proposal`() {
    val buyer = testing.treasury("buyer");
    val seller = testing.treasury("seller");
    val federation = testing.treasury("federation");
    val contract = deployWithState(buyer, seller, federation, 1);
    val newCode = upgradeCode();

    expect(contract.sendProposeUpgradeMessage(federation.address, newCode,
        { value: grams("0.2") })).toHaveSuccessfulTx({
        from: federation.address,
        to: contract.address,
    });
    val (_, proposedAt) = contract.upgradeStatus();

    // 冷静期满前 1 秒：拒绝（UpgradeNotReady），且提案保留（期满可重试）
    testing.setNow(proposedAt + UPGRADE_COOL_DOWN_SECS - 1);
    val res = contract.sendApplyUpgradeMessage(federation.address, { value: grams("0.2") });
    expect(res).toHaveFailedTx({
        from: federation.address,
        to: contract.address,
        exitCode: Errors.UpgradeNotReady,
    });

    val (codeHash, stillAt) = contract.upgradeStatus();
    expect(codeHash).toEqual(newCode.hash());
    expect(stillAt).toEqual(proposedAt);
}

get fun `test APPLY-2 apply after cool down succeeds, clears proposal, emits event`() {
    val buyer = testing.treasury("buyer");
    val seller = testing.treasury("seller");
    val federation = testing.treasury("federation");
    val contract = deployWithState(buyer, seller, federation, 1);
    val newCode = upgradeCode();

    expect(contract.sendProposeUpgradeMessage(federation.address, newCode,
        { value: grams("0.2") })).toHaveSuccessfulTx({
        from: federation.address,
        to: contract.address,
    });
    val (_, proposedAt) = contract.upgradeStatus();

    // 期满（恰好等于 proposedAt + 冷静期）：放行，提案清空，发出 UpgradeApplied 留痕
    testing.setNow(proposedAt + UPGRADE_COOL_DOWN_SECS);
    val res = contract.sendApplyUpgradeMessage(federation.address, { value: grams("0.2") });
    expect(res).toHaveSuccessfulTx({
        from: federation.address,
        to: contract.address,
    });
    expect(res).toEmitExternalMessage<UpgradeAppliedEvent>();

    val (codeHash, clearedAt) = contract.upgradeStatus();
    expect(codeHash).toEqual(0);
    expect(clearedAt).toEqual(0);
}

get fun `test APPLY-3 contract survives apply`() {
    val buyer = testing.treasury("buyer");
    val seller = testing.treasury("seller");
    val federation = testing.treasury("federation");
    val contract = deployWithState(buyer, seller, federation, 1);

    expect(contract.sendProposeUpgradeMessage(federation.address, upgradeCode(),
        { value: grams("0.2") })).toHaveSuccessfulTx({
        from: federation.address,
        to: contract.address,
    });
    val (_, proposedAt) = contract.upgradeStatus();
    testing.setNow(proposedAt + UPGRADE_COOL_DOWN_SECS);
    expect(contract.sendApplyUpgradeMessage(federation.address,
        { value: grams("0.2") })).toHaveSuccessfulTx({
        from: federation.address,
        to: contract.address,
    });

    // 升级后状态机存活：挂的是合约自身 code，行为不变——仍能处理 Deliver（1 → 2）
    expect(contract.sendDeliverMessage(seller.address)).toHaveSuccessfulTx<DeliverMessage>({
        from: seller.address,
        to: contract.address,
    });
    expect(contract.escrowState()).toEqual(2);
}

get fun `test CANCEL-1 cancel clears proposal, then apply is rejected`() {
    val buyer = testing.treasury("buyer");
    val seller = testing.treasury("seller");
    val federation = testing.treasury("federation");
    val contract = deployWithState(buyer, seller, federation, 1);

    expect(contract.sendProposeUpgradeMessage(federation.address, upgradeCode(),
        { value: grams("0.2") })).toHaveSuccessfulTx({
        from: federation.address,
        to: contract.address,
    });

    val res = contract.sendCancelUpgradeMessage(federation.address, { value: grams("0.2") });
    expect(res).toHaveSuccessfulTx({
        from: federation.address,
        to: contract.address,
    });
    expect(res).toEmitExternalMessage<UpgradeCancelledEvent>();

    val (codeHash, clearedAt) = contract.upgradeStatus();
    expect(codeHash).toEqual(0);
    expect(clearedAt).toEqual(0);

    // 提案已清空，再 Apply 必须显式报错（NoUpgradeProposal），不静默
    val apply = contract.sendApplyUpgradeMessage(federation.address, { value: grams("0.2") });
    expect(apply).toHaveFailedTx({
        from: federation.address,
        to: contract.address,
        exitCode: Errors.NoUpgradeProposal,
    });
}

get fun `test CANCEL-2 apply or cancel without proposal is rejected`() {
    val buyer = testing.treasury("buyer");
    val seller = testing.treasury("seller");
    val federation = testing.treasury("federation");
    val contract = deployWithState(buyer, seller, federation, 1);

    val apply = contract.sendApplyUpgradeMessage(federation.address, { value: grams("0.2") });
    expect(apply).toHaveFailedTx({
        from: federation.address,
        to: contract.address,
        exitCode: Errors.NoUpgradeProposal,
    });

    val cancel = contract.sendCancelUpgradeMessage(federation.address, { value: grams("0.2") });
    expect(cancel).toHaveFailedTx({
        from: federation.address,
        to: contract.address,
        exitCode: Errors.NoUpgradeProposal,
    });
}

fun assertProposeUsableInState(state: int) {
    val buyer = testing.treasury("buyer");
    val seller = testing.treasury("seller");
    val federation = testing.treasury("federation");
    val contract = deployWithState(buyer, seller, federation, state);
    expect(contract.sendProposeUpgradeMessage(federation.address, upgradeCode(),
        { value: grams("0.2") })).toHaveSuccessfulTx({
        from: federation.address,
        to: contract.address,
    });
}

get fun `test STATE-1 upgrade lifecycle is orthogonal to escrow state`() {
    // OPEN / LOCKED / DISPUTED / RELEASED——升级是合约生命周期管理，与交易状态机正交
    assertProposeUsableInState(0);
    assertProposeUsableInState(1);
    assertProposeUsableInState(3);
    assertProposeUsableInState(4);
}

// ── COMPAT-1（硬约束 2 的复现机制）：旧格式 extra cell → 新代码 ──────────────────
// 升级只换 code 不动 data。新代码上任第一件事就是读**旧格式**存储（275 bit/0 ref，
// 没有 upgrade 的 Maybe 标志位）——严格解析会因缺位抛错、资金路径全崩。本用例手工构造
// 旧格式 extra 与 data cell（**不走** EscrowExtra.toCell()，它会写成新格式），钉住
// loadExtraCompat 的兼容读取：资金路径不崩、upgradeStatus 默认 (0,0)、Propose 可写入
// 并把 extra 惰性迁成新格式。容错加载器若被移除、回退严格解析，本用例必然红
// （underflow）——红因来自被测层（mutation 实测过，见交付报告）。

// 旧格式 extra cell = 升级前的两字段布局（ownJettonWallet + asset），275 bit / 0 ref
fun compat1LegacyExtraCell(own: address, asset: uint8): cell {
    return beginCell().storeAddress(own).storeUint(asset, 8).endCell();
}

// 整个 data cell 手工构造（EscrowStorage 布局：买/卖/联邦/金额/状态 + extra 引用），
// 末尾 storeRef 接**旧格式** extra——复现「升级后新代码读旧 data」的必然组合
fun compat1LegacyDataCell(buyer: address, seller: address, federation: address, extra: cell): cell {
    return beginCell()
        .storeAddress(buyer)
        .storeAddress(seller)
        .storeAddress(federation)
        .storeCoins(grams("1"))
        .storeUint(0, 8)          // state = OPEN（走 Fund 入金路径）
        .storeRef(extra)
        .endCell();
}

fun deployWithLegacyExtra(buyer: Treasury, seller: Treasury, federation: Treasury): EscrowContract {
    val legacyExtra = compat1LegacyExtraCell(federation.address, 0);   // TON 计价（占位地址同 baseStorage）
    val dataCell = compat1LegacyDataCell(buyer.address, seller.address, federation.address, legacyExtra);
    val stateInit = ContractState { code: build("EscrowContract"), data: dataCell };
    val contract = EscrowContract {
        address: AutoDeployAddress { workchain: 0, stateInit: stateInit, toShard: null }
            .calculateAddress(),
        stateInit: stateInit,
        toShard: null,
    };
    expect(contract.deploy(buyer.address, { value: grams("2") })).toHaveSuccessfulDeploy({
        to: contract.address,
    });
    return contract;
}

get fun `test COMPAT-1 legacy extra cell keeps money paths alive and defaults upgrade`() {
    val buyer = testing.treasury("buyer");
    val seller = testing.treasury("seller");
    val federation = testing.treasury("federation");

    // 布局钉死：旧格式恰为 275 bit / 0 ref——若布局漂移，先红在这里而不是资金断言里
    val legacyExtra = compat1LegacyExtraCell(federation.address, 0);
    expect(legacyExtra.beginParse().remainingBitsCount()).toEqual(275);
    expect(legacyExtra.beginParse().remainingRefsCount()).toEqual(0);

    val contract = deployWithLegacyExtra(buyer, seller, federation);

    // ① 升级字段默认值：旧数据没有提案位 → (0, 0)，不崩
    val (codeHash, proposedAt) = contract.upgradeStatus();
    expect(codeHash).toEqual(0);
    expect(proposedAt).toEqual(0);

    // ①-b 存证字段默认值：旧数据没有第三段 → 空 map（S5 存证扩展的兼容读）；W 读到旧值不崩
    expect(contract.evidenceCount()).toEqual(0);
    expect(contract.ownJettonWallet()).toEqual(federation.address);

    // ② 资金主路径在旧数据上活着：Fund → Deliver → Confirm（各分支都读 extra）
    expect(contract.sendFundMessage(buyer.address, { value: grams("2") })
        ).toHaveSuccessfulTx<FundMessage>({
        from: buyer.address,
        to: contract.address,
    });
    expect(contract.escrowState()).toEqual(1);
    expect(contract.sendDeliverMessage(seller.address)).toHaveSuccessfulTx<DeliverMessage>({
        from: seller.address,
        to: contract.address,
    });
    expect(contract.escrowState()).toEqual(2);
    expect(contract.sendConfirmMessage(buyer.address)).toHaveSuccessfulTx<ConfirmMessage>({
        from: buyer.address,
        to: contract.address,
    });
    expect(contract.escrowState()).toEqual(4);

    // ③ 提案可写入并把 extra 惰性迁成新格式：upgradeStatus 记下 hash 与时刻
    val newCode = upgradeCode();
    val t = testing.getNow();
    expect(contract.sendProposeUpgradeMessage(federation.address, newCode,
        { value: grams("0.2") })).toHaveSuccessfulTx({
        from: federation.address,
        to: contract.address,
    });
    val (hash2, at2) = contract.upgradeStatus();
    expect(hash2).toEqual(newCode.hash());
    expect(at2).toEqual(t);
}

// ── COMPAT-2（尽力项）：新格式 cell → 旧式严格解析的行为记录（降级可行性证据）────────
// 问题：升级后 storage 里的 extra 是**新格式**（EscrowExtra 多了 upgrade 字段）。若将来要把
// code 回滚到旧版本（链上降级），旧 code 会用**旧 struct**（只认 ownJettonWallet/asset）去解析
// 同一份 data——它读得动吗？
//
// **实测结论（2026-09-30，acton 1.2.0 探针 + 本用例）：读不动。**
//   · 旧格式（275 bit / 0 ref）→ 旧式解析通过（对照组，证明布局无误）；
//   · 新格式（276 bit / 0 ref，多一个 `Maybe` 标志位）→ 默认 fromCell 抛 excno 9：
//     `fromCell` 默认带 `UnpackOptions{ assertEndAfterReading: true }`
//     （`.acton/tolk-stdlib/common.tolk:458`，读完字段即 `slice.assertEnd`）；
//   · 只有显式传 `assertEndAfterReading: false` 才读得动，且字段值正确。
//
// 推论（关闭计划里的待验证假设 ⑤）：**链上降级（回滚 code）不可行**——旧 code 读到新 data 会
// 当场抛 9，合约僵死。兼容兜底只能走**方案 c**（全新部署 + 逐单迁移），或让旧 code 显式放宽
// 解析选项。本用例只覆盖「旧式严格解析」；「新 code 读旧 data」是 COMPAT-1（走容错加载器）。

// 旧式布局（升级前的 EscrowExtra：ownJettonWallet + asset）——仅本用例用，模拟旧 code 的解析器。
struct LegacyEscrowExtraCompat2 {
    ownJettonWallet: address
    asset: uint8
}

// 新格式 extra cell = 旧布局 + 1 bit（`EscrowExtra.upgrade: UpgradeProposal?` 的 Maybe 标志，
// 此处 null）。与 T2 落地后的 `EscrowExtra{…, upgrade: null}.toCell()` 布局一致——若新 struct
// 的序列化形态变动，本用例的位长断言会红，提示同步。
fun compat2NewFormatExtraCell(own: address, asset: uint8): cell {
    return beginCell()
        .storeAddress(own)
        .storeUint(asset, 8)
        .storeUint(0, 1)      // Maybe ^UpgradeProposal = null
        .endCell();
}

get fun `test COMPAT-2 new format extra is rejected by legacy strict parse`() {
    val own = testing.treasury("ownWallet").address;

    // 对照组：**旧格式**（275 bit / 0 ref）旧式解析读得动——证明下面的拒绝来自「多出的位」，
    // 而不是本用例把旧布局写错了。
    val legacyExact = beginCell().storeAddress(own).storeUint(1, 8).endCell();
    val parsedLegacy = LegacyEscrowExtraCompat2.fromCell(legacyExact);
    expect(parsedLegacy.ownJettonWallet).toEqual(own);
    expect(parsedLegacy.asset).toEqual(1);

    // 新格式比旧格式恰好多 1 bit（Maybe 标志）、ref 数不变——位布局事实，钉住以便 T2 变动时暴露
    val newCell = compat2NewFormatExtraCell(own, 1);
    expect(newCell.beginParse().remainingBitsCount()).toEqual(276);
    expect(newCell.beginParse().remainingRefsCount()).toEqual(0);

    // 旧式**严格**解析新格式 cell：抛 excno 9（assertEnd 失败）——旧 code 读新 data 必崩
    var strictExit = -1;
    try {
        val _ = LegacyEscrowExtraCompat2.fromCell(newCell);
        strictExit = 0;
    } catch (e) {
        strictExit = e;
    }
    expect(strictExit).toEqual(9);

    // 显式放宽 assertEndAfterReading 才读得动，字段值正确——「要读新 data 必须改解析选项」，
    // 旧 code 不具备该改动，故降级路径不可行（方案 c 兜底）
    val loose = LegacyEscrowExtraCompat2.fromCell(newCell,
        UnpackOptions { assertEndAfterReading: false });
    expect(loose.ownJettonWallet).toEqual(own);
    expect(loose.asset).toEqual(1);
}

// ── SET（两阶段 W 设置，S5 部署）────────────────────────────────────────────────
// 背景：合约地址=H(code,data) 与 W=J(master,合约地址) 是自引用方程（迭代不收敛），
// 故部署时 W=零哨兵、部署后联邦写入真实值（见 types.tolk 的详细说明）。

// 零地址哨兵（与合约 isZeroAddress 同值）——部署时 W 的初始值。
fun zeroAddress(): address {
    return address("0:0000000000000000000000000000000000000000000000000000000000000000");
}

fun deployWithZeroSentinel(buyer: Treasury, seller: Treasury, federation: Treasury,
                           asset: uint8): EscrowContract {
    val contract = EscrowContract.fromStorage({
        buyer: buyer.address,
        seller: seller.address,
        federation: federation.address,
        amount: grams("1"),
        state: 0,
        extra: EscrowExtra {
            ownJettonWallet: zeroAddress(),
            asset: asset,
            upgrade: null,
            evidence: [],
            paused: false,
        }.toCell(),
    });
    expect(contract.deploy(buyer.address, { value: grams("2") })).toHaveSuccessfulDeploy({
        to: contract.address,
    });
    return contract;
}

get fun `test SET-1 federation writes jetton wallet once from zero sentinel`() {
    val buyer = testing.treasury("buyer");
    val seller = testing.treasury("seller");
    val federation = testing.treasury("federation");
    val own = testing.treasury("ownWallet");
    val contract = deployWithZeroSentinel(buyer, seller, federation, 1);

    // 部署时=零哨兵（尚未设置）
    expect(contract.ownJettonWallet()).toEqual(zeroAddress());

    expect(contract.sendSetJettonWalletMessage(federation.address, own.address,
        { value: grams("0.2") })).toHaveSuccessfulTx({
        from: federation.address,
        to: contract.address,
    });
    expect(contract.ownJettonWallet()).toEqual(own.address);

    // 一次性：已有非零值后再设置 = 拒绝（防信任根被事后掉包）
    expect(contract.sendSetJettonWalletMessage(federation.address, federation.address,
        { value: grams("0.2") })).toHaveFailedTx({
        from: federation.address,
        to: contract.address,
        exitCode: Errors.JettonWalletAlreadySet,
    });
}

get fun `test SET-2 non-federation cannot set jetton wallet`() {
    val buyer = testing.treasury("buyer");
    val seller = testing.treasury("seller");
    val federation = testing.treasury("federation");
    val own = testing.treasury("ownWallet");
    val contract = deployWithZeroSentinel(buyer, seller, federation, 1);

    expect(contract.sendSetJettonWalletMessage(buyer.address, own.address,
        { value: grams("0.2") })).toHaveFailedTx({
        from: buyer.address,
        to: contract.address,
        exitCode: Errors.NotFederation,
    });
    // 被拒后哨兵不变（未发生任何写入）
    expect(contract.ownJettonWallet()).toEqual(zeroAddress());
}

get fun `test SET-3 zero wallet write is rejected`() {
    val buyer = testing.treasury("buyer");
    val seller = testing.treasury("seller");
    val federation = testing.treasury("federation");
    val contract = deployWithZeroSentinel(buyer, seller, federation, 1);

    // 把零地址当"写入"既无效、又会烧掉哨兵语义（之后真实设置被 AlreadySet 拒）——显式拒绝
    expect(contract.sendSetJettonWalletMessage(federation.address, zeroAddress(),
        { value: grams("0.2") })).toHaveFailedTx({
        from: federation.address,
        to: contract.address,
        exitCode: Errors.InvalidMessage,
    });
}

// ── EVIDENCE（链上存证，ET-43 链上腿）───────────────────────────────────────────

get fun `test EV-1 parties record evidence, readable by hash and count`() {
    val buyer = testing.treasury("buyer");
    val seller = testing.treasury("seller");
    val federation = testing.treasury("federation");
    val contract = deployWithState(buyer, seller, federation, 0);
    val h1 = beginCell().storeUint(0xABC, 16).endCell().hash();
    val h2 = beginCell().storeUint(0xDEF, 16).endCell().hash();
    val h3 = beginCell().storeUint(0xFED, 16).endCell().hash();
    val t = testing.getNow();

    // 无记录时：count=0、按哈希查 found=0
    expect(contract.evidenceCount()).toEqual(0);
    val (f0, _t0) = contract.evidenceOf(h1);
    expect(f0).toEqual(0);

    // 买方记 h1 → 按哈希可读回（found=1 + 提交时刻）
    expect(contract.sendRecordEvidenceMessage(buyer.address, h1, { value: grams("0.2") })
        ).toHaveSuccessfulTx({
        from: buyer.address,
        to: contract.address,
    });
    val (f1, at1) = contract.evidenceOf(h1);
    expect(f1).toEqual(1);
    expect(at1).toEqual(t);
    expect(contract.evidenceCount()).toEqual(1);

    // 卖方、联邦也各可记（三方都放行）
    expect(contract.sendRecordEvidenceMessage(seller.address, h2, { value: grams("0.2") })
        ).toHaveSuccessfulTx({ from: seller.address, to: contract.address });
    expect(contract.sendRecordEvidenceMessage(federation.address, h3, { value: grams("0.2") })
        ).toHaveSuccessfulTx({ from: federation.address, to: contract.address });
    expect(contract.evidenceCount()).toEqual(3);
}

get fun `test EV-2 non-party cannot record evidence`() {
    val buyer = testing.treasury("buyer");
    val seller = testing.treasury("seller");
    val federation = testing.treasury("federation");
    val contract = deployWithState(buyer, seller, federation, 0);
    val stranger = testing.treasury("stranger");
    val h = beginCell().storeUint(0xABC, 16).endCell().hash();

    expect(contract.sendRecordEvidenceMessage(stranger.address, h, { value: grams("0.2") })
        ).toHaveFailedTx({
        from: stranger.address,
        to: contract.address,
        exitCode: Errors.NotParty,
    });
    expect(contract.evidenceCount()).toEqual(0);
}

get fun `test EV-3 duplicate hash is idempotent (map overwrite, no duplicates)`() {
    val buyer = testing.treasury("buyer");
    val seller = testing.treasury("seller");
    val federation = testing.treasury("federation");
    val contract = deployWithState(buyer, seller, federation, 0);
    val h = beginCell().storeUint(0xABC, 16).endCell().hash();

    expect(contract.sendRecordEvidenceMessage(buyer.address, h, { value: grams("0.2") })
        ).toHaveSuccessfulTx({ from: buyer.address, to: contract.address });
    expect(contract.sendRecordEvidenceMessage(seller.address, h, { value: grams("0.2") })
        ).toHaveSuccessfulTx({ from: seller.address, to: contract.address });

    // 同哈希覆盖写：两条消息过后仍只有一条记录（key 即哈希）
    expect(contract.evidenceCount()).toEqual(1);
}

// ── COMPAT-3（S5 存证扩展）：三种格式节点的读取矩阵 ─────────────────────────────

get fun `test COMPAT-3 evidence segment: old cells default empty, new layout pinned`() {
    val own = testing.treasury("own").address;

    // 276-bit（升级时代）cell：第三段缺失 → loadExtraCompat 给空 map（不崩）
    val mid = compat2NewFormatExtraCell(own, 1);
    expect(mid.beginParse().remainingBitsCount()).toEqual(276);
    expect(loadExtraCompat(mid as Cell<EscrowExtra>).evidence.isEmpty()).toEqual(true);

    // 段 3 格式（含 evidence、无 paused）：手工 cell 恰 277 bit——缺 paused 位读为 false（不崩）
    val seg3Cell = beginCell()
        .storeAddress(own)
        .storeUint(1, 8)
        .storeUint(0, 1)     // upgrade: 无提案（Maybe=0）
        .storeUint(0, 1)     // evidence: 空 map（dict 根 null → Maybe=0）
        .endCell();
    expect(seg3Cell.beginParse().remainingBitsCount()).toEqual(277);
    val parsedSeg3 = loadExtraCompat(seg3Cell as Cell<EscrowExtra>);
    expect(parsedSeg3.evidence.isEmpty()).toEqual(true);
    // 段 3 → 段 4：缺位默认未暂停
    expect(parsedSeg3.paused).toEqual(false);

    // 当前格式（含 evidence + paused 段）空 map 时恰 278 bit / 0 ref——位布局钉住，漂移先红在这里
    val nowCell = EscrowExtra {
        ownJettonWallet: own,
        asset: 1,
        upgrade: null,
        evidence: [],
        paused: false,
    }.toCell();
    expect(nowCell.beginParse().remainingBitsCount()).toEqual(278);
    expect(nowCell.beginParse().remainingRefsCount()).toEqual(0);

    // 旧式严格解析当前格式：抛 excno 9（与 276 时代同机制——链上降级仍不可行）
    var strictExit = -1;
    try {
        val _ = LegacyEscrowExtraCompat2.fromCell(nowCell);
        strictExit = 0;
    } catch (e) {
        strictExit = e;
    }
    expect(strictExit).toEqual(9);
}

// ── PAUSE（紧急暂停，ET-21，2026-10-03）────────────────────────────────────────
// 语义：联邦可冻结【资金与状态推进】；治理（升级三口）与存证**不拦**（暂停≠死锁——
// 暂停的用途正是"冻结业务 → 修复合约 → 恢复"）。
// 实现是白名单式前置守卫（EscrowContract.tolk 的 pauseExemptOp）——本区覆盖矩阵。

get fun `test PAUSE-0 message bodies pin Java codec (op + 32bit, no refs)`() {
    // 与 Java 侧 PauseMessageCodecTest 独立断言同一组三要素（op/位数/引用数）——
    // cell hash 是 (bits, refs) 的确定性函数，两侧三要素同 ⇒ hash 必同（对拍等价）。
    // 注意：beginParse 每次返回**新**解析器——load 与 remaining 必须在同一实例上。
    var pauseSlice = PauseMessage {}.toCell().beginParse();
    expect(pauseSlice.loadUint(32)).toEqual(0x7d5a1015);
    expect(pauseSlice.remainingBitsCount()).toEqual(0);
    expect(pauseSlice.remainingRefsCount()).toEqual(0);

    var unpauseSlice = UnpauseMessage {}.toCell().beginParse();
    expect(unpauseSlice.loadUint(32)).toEqual(0x7d5a1016);
    expect(unpauseSlice.remainingBitsCount()).toEqual(0);
    expect(unpauseSlice.remainingRefsCount()).toEqual(0);
}

get fun `test PAUSE-1 only federation can pause and unpause`() {
    val (contract, buyer, _, federation) = setupLocked();

    expect(contract.sendPauseMessage(buyer.address)).toHaveFailedTx({
        from: buyer.address,
        to: contract.address,
        exitCode: Errors.NotFederation,
    });
    expect(contract.sendUnpauseMessage(buyer.address)).toHaveFailedTx({
        from: buyer.address,
        to: contract.address,
        exitCode: Errors.NotFederation,
    });

    expect(contract.sendPauseMessage(federation.address)).toHaveSuccessfulTx<PauseMessage>({
        from: federation.address,
        to: contract.address,
    });
    expect(contract.sendUnpauseMessage(federation.address)).toHaveSuccessfulTx<UnpauseMessage>({
        from: federation.address,
        to: contract.address,
    });
}

get fun `test PAUSE-2 paused blocks fund (OPEN)`() {
    val buyer = testing.treasury("buyer");
    val seller = testing.treasury("seller");
    val federation = testing.treasury("federation");
    val contract = deployWithState(buyer, seller, federation, 0);   // OPEN

    expect(contract.sendPauseMessage(federation.address)).toHaveSuccessfulTx<PauseMessage>({
        from: federation.address,
        to: contract.address,
    });

    val res = contract.sendFundMessage(buyer.address, { value: grams("1.5") });
    expect(res).toHaveFailedTx({
        from: buyer.address,
        to: contract.address,
        exitCode: Errors.Paused,
    });
    expect(contract.escrowState()).toEqual(0);
}

get fun `test PAUSE-3 paused blocks state transitions (deliver/refund/dispute)`() {
    val (contract, buyer, seller, federation) = setupLocked();   // LOCKED

    expect(contract.sendPauseMessage(federation.address)).toHaveSuccessfulTx<PauseMessage>({
        from: federation.address,
        to: contract.address,
    });

    expect(contract.sendDeliverMessage(seller.address)).toHaveFailedTx({
        from: seller.address,
        to: contract.address,
        exitCode: Errors.Paused,
    });
    expect(contract.sendRefundMessage(buyer.address)).toHaveFailedTx({
        from: buyer.address,
        to: contract.address,
        exitCode: Errors.Paused,
    });
    expect(contract.sendDisputeMessage(buyer.address)).toHaveFailedTx({
        from: buyer.address,
        to: contract.address,
        exitCode: Errors.Paused,
    });
    expect(contract.escrowState()).toEqual(1);
}

get fun `test PAUSE-4 unpause restores operations`() {
    val (contract, _, seller, federation) = setupLocked();   // LOCKED

    expect(contract.sendPauseMessage(federation.address)).toHaveSuccessfulTx<PauseMessage>({
        from: federation.address,
        to: contract.address,
    });
    expect(contract.sendDeliverMessage(seller.address)).toHaveFailedTx({
        from: seller.address,
        to: contract.address,
        exitCode: Errors.Paused,
    });

    expect(contract.sendUnpauseMessage(federation.address)).toHaveSuccessfulTx<UnpauseMessage>({
        from: federation.address,
        to: contract.address,
    });

    // 恢复后同一动作成功
    expect(contract.sendDeliverMessage(seller.address)).toHaveSuccessfulTx<DeliverMessage>({
        from: seller.address,
        to: contract.address,
    });
    expect(contract.escrowState()).toEqual(2);
}

get fun `test PAUSE-5 pause is idempotent (repeat + single unpause restores)`() {
    val (contract, _, seller, federation) = setupLocked();

    expect(contract.sendPauseMessage(federation.address)).toHaveSuccessfulTx<PauseMessage>({
        from: federation.address,
        to: contract.address,
    });
    // 重复 pause：幂等成功（不报"已暂停"——暂停期间消息重传最频繁）
    expect(contract.sendPauseMessage(federation.address)).toHaveSuccessfulTx<PauseMessage>({
        from: federation.address,
        to: contract.address,
    });
    // 单次 unpause 即恢复
    expect(contract.sendUnpauseMessage(federation.address)).toHaveSuccessfulTx<UnpauseMessage>({
        from: federation.address,
        to: contract.address,
    });

    expect(contract.sendDeliverMessage(seller.address)).toHaveSuccessfulTx<DeliverMessage>({
        from: seller.address,
        to: contract.address,
    });
}

get fun `test PAUSE-6 evidence stays available while paused`() {
    val (contract, buyer, _, federation) = setupLocked();
    val h = beginCell().storeUint(0xDEAD, 16).endCell().hash();

    expect(contract.sendPauseMessage(federation.address)).toHaveSuccessfulTx<PauseMessage>({
        from: federation.address,
        to: contract.address,
    });

    // 存证不拦（无损记录——暂停期间仍可固定证据）
    expect(contract.sendRecordEvidenceMessage(buyer.address, h, { value: grams("0.2") })
        ).toHaveSuccessfulTx({ from: buyer.address, to: contract.address });
    expect(contract.evidenceCount()).toEqual(1);
}

get fun `test PAUSE-7 governance (upgrade proposal) stays available while paused`() {
    val (contract, _, _, federation) = setupLocked();

    expect(contract.sendPauseMessage(federation.address)).toHaveSuccessfulTx<PauseMessage>({
        from: federation.address,
        to: contract.address,
    });

    // 提案不拦（见本区头注释：堵死治理会把暂停变成死锁）
    expect(contract.sendProposeUpgradeMessage(federation.address, upgradeCode(),
        { value: grams("0.2") })
        ).toHaveSuccessfulTx<ProposeUpgradeMessage>({
        from: federation.address,
        to: contract.address,
    });
}
