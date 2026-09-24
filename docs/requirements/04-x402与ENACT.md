# x402 Proof Hash 与 ENACT 0% 协议费（整理）

> 来源：用户提交的《x402 Proof Hash 与 ENACT 的 0% 协议费模式》说明。
> 本文是整理 + 独立核实 + 对本项目的影响分析；「整理观察」为整理者补充，非原文。
> 核实方式：`web_search` 两条独立来源 + 项目内 `grep`，详见文末「独立核实」。

## 一、x402 Proof Hash：交付证明上链

### 1.1 原文要点
TON Agent Kit 的托管合约中，买家确认收货时调用 `DeliveryConfirmed` 并传入一个 `x402TxHash` 字符串。
合约把该哈希**永久存储在链上**，同时向合约的存储费池增加 **0.003 TON**（防止合约因存储不足被冻结）。

原文 Tolk 代码（示意）：
```tolk
fun handleDeliveryConfirmed(in: InMessage, proofHash: string) {
    throw_unless(401, in.senderAddress == storage.buyer);   // 1. 验证发送者是买家
    storage.deliveryProofHash = proofHash;                  // 2. 链上存证
    storage.deliveryConfirmed = true;
    storage.storageFund += 3000000;                         // 3. 0.003 TON 存储费池
    storage.maintenanceDeadline = now() + storage.maintenancePeriod; // 4. 进入维护期
}
```

**Proof Hash 可存什么**：卖家发货凭证的 IPFS 哈希（物流单号截图、交付文件哈希）、
x402 支付交易的 txHash、任何可验证的交付证明。

**关键优势**：`escrowData()` getter 可暴露 `x402ProofHash`，争议仲裁时双方与投票人都能在链上读取，
验证"买家确认收货时到底确认了什么"——比单纯存消息哈希更有证明力。

**原文注意**：TON 官方文档要求接收 Jetton 转账的合约**必须验证发送者的 Jetton 钱包地址**，
不能信任 `JettonNotify` 消息中声称的发送者；`x402ProofHash` 的存储逻辑应**独立于** Jetton 验证逻辑。

### 1.2 原文落地建议（P0/P1/P2）
P0 合约增加 `deliveryProofHash` 字段；P0 前端增加「交付证明」输入；
P1 平台费可配置（默认 0%）；P1 在 `escrowData()` 暴露 `x402ProofHash`；P2 探索增值服务模式。

## 二、ENACT 的 0% 协议费模式

### 2.1 原文要点
ENACT 是 TON 上的托管协议，核心卖点是 **0% 协议费**——所有资金直接给到服务提供方。
覆盖成本的方式：Gas 预留（返回未使用 Gas）、存储费池（类似 `storageFund`）、由运营者自行决定是否收费。

### 2.2 平台费模式权衡（原文）

| 模式 | 优势 | 挑战 |
|---|---|---|
| 平台费 0% | 用户门槛最低，推广竞争力强 | 运营者无收入，需其他方式覆盖成本 |
| 平台费 0.5-1% | 运营者可持续 | 用户有感知成本 |

原文建议：开源代码里把平台费做成**可配置项（默认 0%）**，运营者自行调整。0% 时的成本覆盖途径：
交易群增值服务（置顶推荐）、向商家收额外费用、接受捐赠。

**原文成本事实**：Telegram Mini Apps 通过 **Stars** 支付时平台 0% 佣金，但 Stars 兑换汇率有损耗
（用户支付 $2.40，开发者可能只收到 $1.30）；走 **TON 直接收费**则无此损耗。

## 三、整理观察（补充，非原文）

### 3.1 x402 协议本身不适用于本项目场景，但其"交付证明"概念可用
核实表明 **x402 是 Coinbase + Cloudflare 的 HTTP-402 支付协议**，定位是 **AI agent 之间的 API 支付**
（agent-native payment rail）。而本项目是 **Telegram 群成员之间的人-人担保交易**，支付路径是
TON Connect + 托管合约，**不走 x402**。
所以"x402 Proof Hash"对本项目可借鉴的是**概念**——「买家确认收货时把一份可验证的交付证明哈希上链」，
而非引入 x402 协议。命名上建议叫 **`deliveryProofHash`（交付证明哈希）**，不要绑定 x402 字样，
免得后续读者以为要接 x402 支付。

### 3.2 ENACT 是「每 job 一个独立合约」——恰与项目「每笔交易独立地址」呼应
核实：ENACT 的 "Each job is a standalone smart contract"，流程为
`Client locks funds → Provider works → Evaluator approves → Payment releases automatically`。
这**回答了 03 号文档留下的一个问题**（「每笔独立地址」与 TON Pay `recipientAddr` 的关系）：
业界（ENACT）的做法是**每笔交易一个独立合约实例**，天然实现资金隔离。
但注意 ENACT 是 **agent-to-agent** 场景，本项目是 **human-to-human**，仅可作**参考实现**而非直接复用。

### 3.3 `storageFund` 是 TON 的真实约束，不是可选项
TON 合约需为链上存储付费，**存储余额耗尽合约会被冻结**。原文的 `storageFund += 0.003 TON`
是对该约束的应对。S5 合约设计**必须**考虑存储费机制（尤其是"永久存储 proofHash"这类诉求会持续吃存储）。

### 3.4 `deliveryProofHash` 对本项目代码的影响（需评估）
本项目 `EscrowOrder` 8 态状态机**没有** proof hash 字段（grep 全仓无 `proofHash`/`deliveryProof`）。
若要落地，涉及：
- `EscrowOrder` 加字段 + **V2 Flyway 迁移**（`escrow_orders` 加列）；
- 交货/确认收货流程（`markDelivered`/买方确认）传入哈希；
- 与 **T40（链上存证）**、**T65（资金流向链上验证）** 归并——三者都在做"关键信息上链"，
  应统一为一个"链上存证"能力而非三套。
- ⚠️ 这会改动 Wave 1a 之外的状态机，属于**需用户确认的范围扩张**。

### 3.5 平台费 0% 与既有待决 B1 一致——可直接强化
项目 `docs/UNDECIDABLE-DECISIONS.md` 的 **B1（平台费与联邦费用比例）** 此前建议「费率做配置项、默认 0」，
与本文「平台费可配置、默认 0%」**完全一致**。本文额外提供了两条运营侧信息（Stars 汇率损耗、增值服务模式），
可并入 B1 供决策。

### 3.6 Jetton 发送者验证：与项目既有结论一致
本文重申「必须验证 Jetton 发送者合约地址、不信任 `JettonNotify` 声称的发送者」——
与项目 `docs/UNDECIDABLE-DECISIONS.md:160` 已有结论**一致**（jetton 转账是多消息流，托管合约必须校验发送者合约地址）。无冲突。

### 3.7 原文代码为示意，语法须以 Tolk 官方为准
示例里的 `fun handleDeliveryConfirmed(...)`、`now()`、`storage.xxx` 混用了 FunC 与 Tolk 风格。
落 S5 代码前须以 `docs.ton.org` 的 Tolk 语法与 Acton 工具链为准，**不要照抄本示例**。

## 四、独立核实（本次整理所做）

| 断言 | 核实方式 | 结果 |
|---|---|---|
| x402 是真实协议 | web_search（xpay.sh / aurpay.net / dev.to 多源） | ✅ 属实：Coinbase + Cloudflare 的 HTTP-402 agent 支付协议 |
| TON Agent Kit 存在、含 x402 与 escrow | web_search（`github.com/Andy00L/ton-agent-kit`） | ✅ 属实：21 npm 包，含 "on-chain escrow with disputes"、"x402 paid endpoints" |
| TON Agent Kit 有 `get_delivery_proof` / `pay_for_resource` | web_search（其 `docs/x402-protocol.md`） | ✅ 属实：按 TX hash 或 escrow ID 取交付证明 |
| ENACT 是 TON 上托管协议、每 job 独立合约 | web_search（`github.com/ENACT-protocol/enact-protocol`） | ✅ 属实，原文 "Each job is a standalone smart contract" |
| ENACT 为「0% 协议费」 | web_search | ⚠️ **未独立证实**——搜索命中的是"trustless escrow"卖点，未见明确的"0% 协议费"表述；以官方文档为准 |
| Telegram Stars 汇率损耗（$2.40→$1.30） | 未核实 | ⚠️ **待核实**（若采纳 Stars 收费路径，落代码前查官方费率） |
| 项目内已有 proofHash / x402 / ENACT 相关实现 | grep 全仓 | ❌ 无实现，纯新增 |

## 五、给决策的收敛（建议）

1. **可立即采纳（无需用户额外拍板）**：`deliveryProofHash` 作为 S5 的一个设计要点记入（概念清晰、与 T40 归并）。
2. **需用户拍板**：`EscrowOrder` 是否加字段 + V2 迁移（范围扩张）；平台费定价（强化 B1）。
3. **不建议引入**：x402 协议本身（场景不符）、ENACT 代码直接复用（场景不符，仅作参考）。
