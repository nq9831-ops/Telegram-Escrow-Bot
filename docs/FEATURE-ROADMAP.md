# 功能路线图（剩余 74 项 + 5 个结构性缺口）

> 归档时间：2026-09-23 · 基线：**193 项测试全绿**（tgg-core 71 / tgg-federation 13 /
> tgg-escrow 97 / tgg-chain 12）
>
> 每项给出**可直接开工的规格**（输入→输出→守卫→边界），让续跑不必重新推导。
> 编号沿用原文档 G1–G30 / T1–T58。

---

## 0. 当前已落地（对照用）

| 编号 | 能力 | 落地类 |
|---|---|---|
| G5 | 违禁词过滤 | `BannedWordMatcher`（Trie 未做，见 G5 备注） |
| G22 | 权限系统 | `PermissionChecker` / `PermissionPolicy` / `MemberRole` / `CommandActor` |
| G24 | 日志脱敏 | `LogSanitizer` |
| G28/G29 | 风险评分 + 阈值拒绝 | `JoinRiskScorer` 一族 |
| T12 | 交易状态机 | `EscrowOrder`（8 态） |
| T15/T16 | 多签仲裁 + 规则校验 | `EscrowMultiSigRule` / `EscrowVerdictGuard` / `PartySignatureVerifier` / `FederationPartySignatureVerifier` |
| T23/T24/T25 | 单笔限制 / 冷却期 / 争议冻结 | `TradeAdmissionGate` 一族 |
| T1（部分） | 交易创建入口 | `EscrowTradeService` + `TradeHistoryPort` + `EscrowOrderStore`（JPA 实现已落地） |
| — | 命令解析 | `CommandParser` / `BotCommand` |
| — | 联邦签名 | `FederationKeyPair` / `FederationException` |
| — | 持久化地基 | `EscrowOrderRepository` / `JpaEscrowOrderStore` / `JpaTradeHistoryPort` / `V1__init_escrow.sql` |
| G7/G9/G10/G26/G23 | 波次 G-A 内容安全与限流 | `MessageRepeatDetector` / `LinkFilter` / `MediaFilter` / `SlidingWindowCounter` / `RateLimiter`（提交 `8046ed7`，tgg-core 71→107） |
| T2/T13/T27/T14/T28/T18/T26/T38 | 波次 T-A 交易流程深度 | `TradeStatusView` / `TradeTimeoutPolicy` / `DisputeFlow` / `TradeReview` / `MaintenanceWindow` / `TradeStage`（提交 `b4ec90f`，tgg-escrow 97→128） |
| G11/G12/G13/G14/G15/G19/G27 | 波次 G-B 入群与配置 | `WelcomeTemplate` / `JoinVerificationFlow` / `FeatureToggle` / `AdminRegistry` / `SilenceWindow` / `ProtectionMode`（提交 `c4d6ca0`，tgg-core 107→152） |
| S2 | 命令层接线 | `TradeCommandHandler`（提交 `eacc780`，tgg-app 0→8） |

> 波次 **G-A** / **T-A** / **G-B** / **S2** 已完成（`mvn test` 全绿 193→313）。另修复 `MessageRepeatDetector` 内容 key 无界增长、`RateLimiter` 跨层计数波及全群两处缺陷。第 2、3 节中这些条目已落地，续跑请跳过。
>
> **Wave 1b（T-B 群聊投票）/ 1c（V2.0 新增纯逻辑）及后续波次已按用户指令暂停**，待用户后续文档与指示。

---

## 1. 结构性缺口（先于功能，否则功能无处安放）

| # | 缺口 | 规格 |
|---|---|---|
| S1 | **零用户可见表面** | `TelegramBotHandler` 实现 `SpringLongPollingBot`；`onUpdateReceived` → `CommandParser.parse(text, botUsername)` → `PermissionChecker.isAllowed` → 分发到服务 → 回复文案。**需要 A2 的 token**。回复文案要过 `LogSanitizer`。 |
| S2 | **命令层接线** | `TradeCommandHandler.handle(BotCommand, CommandActor) → String`：`/escrow create <卖方> <金额> <币种>` → `EscrowTradeService.initiate` → 「已创建订单 #N」或「被拒：<原因>，可重试：<时刻>」。纯逻辑可测，是 Telegram 级用户验收的最后一环。 |
| S3 | **Bot 内 6 项入口** | T1 完整化、T2 状态查询（`TradeStatusView`）、T3 信用分联动、T4 与群管理联动、T5 建群引导、T6 公告。 |
| S4 | **真实链上源** | `HttpChainSource implements ChainSource`（对接 TON HTTP API），两个实例做双源交叉（复用 `BalanceCrossVerifier`）。**需确认 TON 的确认数语义**（见待决文档 D 节）。 |
| S5 | **TON 托管合约** | Tolk 编写 `EscrowContract`：`fund/release/refund/dispute/resolve`。守卫：`impure` 修饰、减法前验余额、commit-reveal 随机数、**校验 jetton 发送者合约地址**（防 Fake Jetton，T39）。多签语义见待决文档 C1。 |

> **Wave 5 技术输入（2026-09-24 补）**：TON Pay 支付层 SDK 与 Tolk 合约选型见 `docs/requirements/03-TON-Pay与Tolk.md`。
> 要点：① Tolk 已是本项目既定选型（FunC 已 legacy、编译器不再维护），S5 是**从零用 Tolk 写**而非迁移；
> ② 接 TON Pay 前需先补 **Web/HTTP 端点**（项目当前无 Web 层，webhook 无处落）——建议独立立项；
> ③ 「每笔交易独立地址」与 TON Pay 的 `recipientAddr` 关系需在 S5 设计时一并确定。

---

## 2. 群管理剩余 25 项（tgg-core）

### 波次 G-A：内容安全与限流（纯逻辑，可立刻开工）

| 编号 | 规格 |
|---|---|
| G7 | `MessageRepeatDetector`：消息内容哈希 → 窗口内计数 → 达阈值判刷屏。阈值/窗口注入。边界：空消息、Unicode 归一化、窗口滑出后计数衰减。 |
| G9 | `LinkFilter`：白名单域名 + URL 检出（短链域名为可疑）。返回命中 URL + 是否白名单。边界：无 scheme 的裸域名、大小写、端口。 |
| G10 | `MediaFilter`：MIME/扩展名白名单判定。边界：双扩展名（`.jpg.exe`）应按后者判。 |
| G26 | `SlidingWindowCounter`：N 秒内计数超阈值触发。时钟注入。边界：恰好等于阈值（含/不含要定死）、窗口滑出。 |
| G23 | `RateLimiter`：用户/群组/全局三层独立阈值，任一层超限拒绝并**说明是哪一层**。 |

### 波次 G-B：入群与配置

| 编号 | 规格 |
|---|---|
| G11 | `WelcomeTemplate`：模板 + `{username}` 等变量替换。变量缺失时保守（不泄漏 `{...}` 原文）。 |
| G12/G13 | `JoinVerificationFlow`：申请→验证中→通过/拒绝/超时四态。超时可配置。只做状态与守卫。 |
| G14 | `FeatureToggle`：按群组开关，**未登记功能默认关闭**（fail-closed）。 |
| G15 | 管理员配置：添加/移除 + 权限继承。 |
| G19 | `SilenceWindow`：时段判定，**跨午夜时段要正确**（22:00–02:00）。时钟注入。 |
| G27 | `ProtectionMode`：开/关 + 原因；开启时拒绝新入群申请。 |

### 波次 G-C：需外部依赖

| 编号 | 规格 | 阻塞 |
|---|---|---|
| G1 | 踢出/封禁/解封 | 需 Telegram API 端口（`GroupAdminPort`）+ S1 |
| G2 | 禁言/解除禁言（定时） | 同上 |
| G3 | 警告/清除警告（累计） | 需持久化迁移 V2（`warnings` 表） |
| G4 | 群主自主封禁（独立 API） | 需 Web 端点 |
| G6 | 反刷屏（Redis 滑动窗口） | 需 Redis 集成（可先用内存版 + 接口） |
| G8 | 消息自动删除 | 需 Telegram API 端口 |
| G16 | 日志频道（操作推送） | 需 Telegram API 端口 |
| G17 | 群组信息同步（定期） | 需 Telegram API 端口 |
| G18 | 定时任务 + 自动回复 | 需调度器 |
| G20 | 年龄门槛流程 | 需待决 B4（合规口径） |
| G21 | 分级权限策略 | 需待决 B4 |
| G25 | 数据备份 | 需运维决策 |
| G30 | 第二渠道验证入口 | 需选型（待决） |

---

## 3. 担保交易剩余 49 项（tgg-escrow / tgg-chain / Web）

### 波次 T-A：交易流程深度（纯逻辑，可立刻开工）

| 编号 | 规格 |
|---|---|
| T2 | `TradeStatusView`：8 态 → 用户可读摘要 + 「下一步该谁做什么」。不含敏感字段。 |
| T13/T27 | `TradeTimeoutPolicy`：(状态, 进入时刻, 当前时刻) → 是否超时 + 触发动作（自动确认/自动退款）。时长可配置。 |
| T14/T28 | `DisputeFlow`：争议发起守卫（谁能发、何状态能发）+ 维护期倒计时暂停时刻计算。 |
| T18 | `TradeReview`：评价守卫（仅终态可评、双方各一次、评分范围）。 |
| T26 | `MaintenanceWindow`：5 选项选维护期时长，默认项可配置；到期判定。 |
| T38 | 交易阶段标注：状态 → 阶段（下单/履约/确认/维护/争议/结算）。 |

### 波次 T-B：群聊留痕与投票

| 编号 | 规格 |
|---|---|
| T31/T32 | `TradeGroupLifecycle`：待建→已关联→留痕中→静默期→已归档。静默期可配置。 |
| T33 | `MemberVote`：发起/计票/结果判定，参与门槛可配置。 |
| T34 | `VoterRecusal`：与买卖双方近期有交易者、利益关联者不得投票。窗口可配置。 |
| T35 | `EvidenceChannel`：双通道（群内记录 + 可选上链）**各自成功/失败分别上报**，不能一条失败静默丢另一条。 |
| T36 | `VoteRewardAllocator`：按策略分配押金给投对者。**总额守恒**（分出 ≤ 收入，违反抛异常）。 |
| — | `VoteStake`：质押锁定/没收/退还的状态与守卫。 |

### 波次 T-C：安全增强

| 编号 | 规格 |
|---|---|
| T37 | 重放保护：每请求绑定唯一 seqno/nonce，重复即拒。 |
| T39 | Fake Jetton 验证：**校验 jetton 发送者合约地址**在可信列表内（防伪造代币）。 |
| T40 | 链上存证：关键节点哈希上链。 |
| T41 | 策略化准入配置。 |
| T42 | 防钓鱼提示文案注入。 |
| T43 | 代理合约可升级性（**默认关闭**，见待决 C2）。 |
| T20 | 合约审计支持（构建产物 + 验证脚本）。 |
| T21 | 时间锁 + 紧急暂停（时长可配置）。 |

### 波次 T-D：人性化 + 反滥用

| 编号 | 规格 |
|---|---|
| T44 | 首单保障（**规则待决 B2**，建议先做免首单手续费）。 |
| T45 | 主动推送（关键节点通知）。 |
| T46 | 争议双方陈述流程（含确认已读）。 |
| T47 | 交易信用可视化（**依赖 B3 信用分模型**）。 |
| T48 | 多维度惩罚（单笔限制+冷却+投票暂停+公示+链上存证）。 |
| T49 | 社区氛围建设（每日提示/周榜/月报）。 |
| T50 | 设备关联检测（同设备/IP 关联多账号 → 标可疑）。阈值可配置。 |
| T51 | 互刷模式检测（A/B 反复交易 → 标可疑）。 |
| T52 | 新账号交易限制。 |
| T53 | 互刷不累加信用（**依赖 B3**）。 |
| T54 | 可疑交易关系标记。 |
| T55/T56 | Comment 钓鱼提示 / 官方不通过 comment 发奖励。 |
| T57 | 账号异常检测。 |
| T58 | 定期防钓鱼推送。 |

### 波次 T-E：Web 平台（T7–T11、T22、T29/T30）

T7 Web 交易界面（Vue 3）、T8 Telegram OAuth、T9 TON USDT 支付、T10 合约托管、
T11 资金冻结/释放/退款、T22 KYC（**待决 B4**）、T29/T30 费用划转（**待决 B1**）。
**全部依赖 S5 合约 + A2 token + 待决项**，是最靠后的一波。

---

## 4. 建议的续跑顺序

1. **先解 A1**（子代理余额）——它是并行能力的唯一瓶颈，解了之后 74 项可以 4 路并行。
2. 波次 **G-A + T-A**（纯逻辑、零依赖，约 10 个类 + 10 个测试类）——立刻可开工。
3. 解 A2（token）→ S1 + S2 + S3（**用户级验收在此收口**）。
4. 波次 **G-B + T-B + T-C**。
5. 解 B1/B2/B3 → 波次 **T-D**。
6. S5 合约（解 C1）→ 波次 **T-E**。

---

## 5. 验收口径（续跑时照此判定「完成」）

- 每个能力：**先写测试（RED：目标类不存在→编译失败）→ 实现（GREEN）**。
- 每波结束：`mvn test` 全绿，并把新增测试数写进提交信息。
- **用户级验收**（不是单元测试）：对 Telegram 端，用户发 `/escrow create …` 看到
  「已创建订单 #N」或「被拒：<原因>」；对群管理，用户发消息触发对应处置并看到回执。
  在 S1/S2 落地前，用户级验收**无法执行**，不要声称已达成。
