# TON Pay 集成与 Tolk 合约（整理）

> 来源：用户提交的《TON Pay 与 Tolk 的迁移》说明。本文是整理 + 独立核实 + 对本项目的影响分析；
> 「整理观察」为整理者补充，非原文。
> 核实方式：`web_search` 两条独立来源 + 项目内 `grep`，详见文末「独立核实」。

## 一、TON Pay 集成方案

### 1.1 TON Pay 是什么（原文）
TON 基金会于 2026 年 2 月推出的**共享支付层 SDK**，目标是让 Telegram 用户直接在应用内完成加密支付。

| 特性 | 说明 |
|---|---|
| 钱包无关 | 支持 Tonkeeper、MyTonWallet、Telegram 内置钱包等，经 TON Connect 连接 |
| 亚秒级结算 | 交易通常 1 秒内完成，平均手续费 < 0.01 美元 |
| 无需自定义钱包逻辑 | SDK 封装支付状态轮询与结算确认 |
| 支持订阅和定期支付 | 未来支持订阅模式，适合平台费收取 |
| Webhook 通知 | 支付完成后自动 POST 到后端，无需轮询 |

### 1.2 集成架构（原文）

**客户端**（Web 前端）：`useTonPay()` 提供 `pay()`，回调里用 `createTonPayTransfer({amount, asset, senderAddr, commentToSender, commentToRecipient, chain})`
构造支付消息；返回 `{message, reference, bodyBase64Hash}`。`pay` 自动检查钱包连接，未连接则先弹 TON Connect 选钱包界面。
`reference` 与 `bodyBase64Hash` 需落库，用于后续追踪。

**服务端**：TON Pay 在支付完成后 POST 交易详情到后端，后端须做**六步验证**：
1. 验签（`X-TonPay-Signature`，HMAC-SHA256，用 `TONPAY_API_SECRET`）；
2. 只处理 `transfer.completed` 事件；
3. 用 `reference` 匹配订单；
4. 防重复处理（订单已 completed 直接返回成功）；
5. 校验金额与币种与订单一致；
6. 处理成功（标记完成 + 记 txHash）。

### 1.3 对本项目担保交易的集成建议（原文）

担保交易有「锁定资金」与「释放资金」两个关键动作，TON Pay 可用于**买方锁定资金**这一步：
前端 `createTonPayTransfer` → 后端存 `reference`/`bodyBase64Hash` → 用户经 TON Connect 签名 →
资金转入**托管合约地址**（而非 TON Pay 商户地址）→ webhook 通知 → 后端调 `EscrowContract.fund()` 锁资。

**关键点**：`recipientAddr` 设为**托管合约地址**，资金直接进合约，减少一个中转环节。

**API Key 的作用**：不用 Key 支付仍能上链，但拿不到 webhook 通知与 Dashboard，只能手动轮询链上状态。

## 二、Tolk 合约（原文称「迁移」）

### 2.1 为什么用 Tolk（原文）
TON 官方文档明确：**FunC 编译器已不再维护，Tolk 是唯一被积极支持的合约语言**。

收益：Gas 降 30-50%、消除 `impure` 陷阱（FunC 忘了写 impure 编译器可能丢弃安全检查调用）、
自动序列化（struct 自动 toCell/fromCell）、强类型（bool/address/null safety/union types）、
更清晰的错误信息（如 `blockchain.logicalTime()`）。

### 2.2 工具（原文）
`npx func-to-tolk convert <合约>.fc` 增量转换；Blueprint 支持 `npx blueprint create MyContract --type tolk-empty`。

### 2.3 合约结构（原文示例）
`struct EscrowStorage { buyer, seller, amount, state(uint8), maintenancePeriod(uint32), disputeDeadline(uint64) }`；
消息 struct（FundMessage/ReleaseMessage/DisputeMessage/ResolveMessage）；
`onInternalMessage` 用 `lazy Msg.fromCell` + `match` 路由（替代 FunC 的 opcode 链式判断）；
`lazy` 让只加载实际访问的字段以省 Gas。

### 2.4 安全实践（原文）

| 实践 | Tolk 实现 |
|---|---|
| 发送者验证 | `throw_unless(401, in.senderAddress == storage.buyer)` |
| 整数溢出防护 | 减法前 `throw_unless(998, balance >= amount)`；coins 类型不会变负 |
| 弹回消息处理 | `onInternalMessage` 通过 `in.bounced` 判断 |
| Fake Jetton 验证 | `in.senderAddress == trustedJettonWallet` |
| 重放保护 | `queryId`/`seqno` |

### 2.5 部署（原文）
`npx blueprint run deployEscrow --mainnet --tonconnect`；部署后在 Tonviewer/Tonscan 查看，
建议 README 公示合约地址与源码链接供用户验证。

## 三、原文综合建议（P0/P1/P2）

| 优先级 | 任务 |
|---|---|
| P0 | 申请 TON Pay Merchant API Key（获取 webhook 与 Dashboard，避免轮询） |
| P0 | 用 Tolk 重写 EscrowContract |
| P1 | 集成 TON Pay 前端按钮（`@ton-pay/ui-react`） |
| P1 | 实现 TON Pay webhook 验证（验签/金额/币种/防重复） |
| P2 | 用 Blueprint 重新部署合约，公示地址与源码 |

## 四、整理观察（补充，非原文）

### 4.1 Tolk 是「选型」，不是「迁移」——本项目无 FunC 合约可迁
`grep` 显示本项目 **README 技术栈已写「合约语言 Tolk」**（`README.md:60`）、
`FEATURE-ROADMAP.md:45` 的 S5 也是「Tolk 编写 EscrowContract」，而 **S5 一行代码都没写**
（`docs/UNDECIDABLE-DECISIONS.md:160` 只留了 jetton 多消息流的检索结论）。
所以对本项目而言："迁移"措辞不成立——是**从零用 Tolk 写**（选型已定），无需 `func-to-tolk` 转换器。
真正待办的是 S5 的实现本身，不是语言迁移。

### 4.2 TON Pay 引入一个结构性缺口：项目当前没有 HTTP 端点
webhook 验证要求后端**暴露一个 HTTP 端点**接收 POST。`grep` 确认项目**没有任何 Web 层**
（无 `@RestController`、无 Spring Web 依赖，S1 甚至还没接 Bot）。
这意味着接 TON Pay 前，必须先补一层 Web 服务——这是**独立于业务逻辑的基础设施缺口**，
应作为前置项而不是顺手加。

### 4.3 TON Pay 与「每笔交易独立地址」的关系需理清
V2.0 架构原则写「每笔交易独立地址」（资金隔离），而 TON Pay 的 `recipientAddr` 指向**托管合约地址**。
两者并存时会遇到：是"一个合约服务多笔（内部用 per-order 子账户隔离）"还是"每笔一个合约实例"？
原文没答。这与待决 C1（多签语义）、C2（合约可升级性）交织，需在 S5 设计时一并定。

### 4.4 第三方 SDK 的依赖与信任边界
TON Pay 是第三方 SDK（基金会 + 合作方 RSquad）。引入它意味着：
- 依赖 `github.com/RSquad/ton-pay` 的 SDK 与 TON Pay 的托管式 webhook 通道；
- **API Key 是凭据**（`TONPAY_API_SECRET`）——按项目「服务器不碰私钥」的精神，它是**必须安全保管的服务端密钥**，
  需走环境变量注入、禁入库（`.gitignore` 已挡 `.env`）。
- 若基础链上资金不经 TON Pay（自行调合约），webhook 只是"加速通知"，非必需——存在"不依赖第三方 SDK"的替代路径。

### 4.5 与待决事项的勾连
- **C1（多签语义）**：TON Pay 只解决"买方锁资"这一端，释放/退款仍走合约多签，C1 未解则 S5 无法定稿。
- **D（TON 确认数语义）**：webhook 说"结算完成"与链上"最终确认"是否同一口径，需核实。
- **F（钱包取向）**：TON Pay 经 TON Connect 连接，与 F 节的托管/自持张力同源。

## 五、独立核实（本次整理所做）

| 断言 | 核实方式 | 结果 |
|---|---|---|
| TON Pay 由 TON 基金会 2026-02 推出 | web_search（KuCoin / coinalertnews / TON Adoption 多源） | ✅ 属实；合作方 RSquad，SDK 仓库 `github.com/RSquad/ton-pay` |
| TON Pay webhook 用 HMAC-SHA256 | web_search（`ton-blockchain.github.io/docs/applications/ton-pay/webhooks`） | ✅ 属实，文档原话 "signs it using HMAC-SHA256 … optional API key" |
| FunC 编译器不再维护、Tolk 为官方语言 | web_search（`docs.ton.org/languages/func/overview`） | ✅ 属实，原话 "The official smart contract language of TON Blockchain is Tolk. FunC is now a legacy language" |
| Tolk 是 Acton 工具链的原生语言 | web_search（TON Adoption） | ✅ Acton v1.0 于 2026-05-11 发布 |
| 项目内已有 Tolk/TON Pay 代码 | grep 全仓 | ❌ 无实现；仅 README/路线图/待决文档中的文字提及 |
| 用户材料中的包名 `@ton-pay/ui-react`、`@ton-pay/api`、`createTonPayTransfer` | 未逐一核实 | ⚠️ **待核实**——以官方仓库 `RSquad/ton-pay` 的 README 为准，落代码前必须核对真实包名与 API |
