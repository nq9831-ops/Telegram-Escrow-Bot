# 代码 vs 规格 差距对比（**实测重建版**）

> **本文档取代 `HEAD=91c5642` 时点的旧快照。** 旧快照有两重过时：
> ① 当时就漏标（如 SPEC 标 ET-34 ⬜ 而代码早已落地 `RiskPrompt` + `PendingTradeRegistry`）；
> ② 此后又新增大量实现类（实测 main 类数已是旧快照的 2.5 倍）。
>
> **对照基准**：`docs/requirements/SPEC.md`（其状态表共 113 行；本文档逐项 114 行 = GM 36 + ET 78，
> 多出的 GM-36 取自 SPEC 6.6 节的定义——SPEC 状态表本身漏了该行）
> **代码实测**：`HEAD=14bbf6d`、**main 164 类 / test 107 类**、`mvn verify` **805 用例 + 4 个 IT 全绿**
> **测量方式**：`find`/`grep`/`awk` 逐类实测，非文档转述。
>
> ⚠️ **证据分级（本版新增，用于避免再犯"有类即算完成"）**
> - **A｜已落地·已接线**：实现类存在 **且** 我能指出它挂在哪条运行链上（本会话读到过该链）。
> - **B｜已落地·未接线**：类存在，但**没有任何生产调用点**（零引用孤儿），用户实际用不到。
> - **C｜部分实现**：有类，但缺关键件（链上/调度器/持久化/交互）。
> - **D｜未找到实现类**：本次实测未定位到对应实现。
> - **E｜未核实**：我没读到决定它的那段实现，**不作断言**。
>
> 局限：A/B/C 的判定以**类名与模块归属 + 本会话读过的调用链**为证据，未逐类做行为级核实；
> 标 E 的项就是"我没证据"的意思，不是"没做"。

## 1. 头号更正：旧快照的结构性结论**已失效**

旧快照断言"真正**用户可用**的功能为 0——因为无 Bot 接线（S1）、无 Web 层（S6）、无链上合约（S5）"。
前两项**不再成立**（第三项仍是缺口）：

| 旧快照的说法 | 实测（HEAD 14bbf6d） |
|---|---|
| 无 Bot 接线（S1）→ 功能对用户不可见 | ❌ 已失效。`BotWiring` + `BotDispatcher` + `TelegramBotHandler` 在，`/escrow …` 命令经 `TradeCommandHandler` → `EscrowTradeService` 打通；本会话多次沿该链改测 |
| 无 Web 层（S6） | ❌ 已失效。`TradeApiController` + `TradeInviteController` + `WebAppInitDataVerifier`（`initData` 验签）在 |
| 无 TON 合约（S5） | ✅ 仍成立。`contracts/EscrowContract.tolk` 只是**可编译骨架**（`acton build` 过，但资金逻辑未实现、未部署） |

**但这不等于"可用"已达预期**：真机路径未核销（`docs/ONLINE-VERIFICATION.md` 的 A9/A10/A11），
且链上托管（ET-09/10/11 的资金面）仍是空的——现在的"资金"只是**状态登记**。

## 2. 逐项实测（113 条）

### 2.1 群管理（GM 01–36）

| 编号 | 功能 | 判定 | 证据（实测） |
|---|---|---|---|
| GM-01 | 踢出/封禁/解封 | C | `GroupAdminPort` + `TelegramGroupAdminAdapter` + `ModerationCommandHandler`；**"解封"未核实**（端口只见 kick/ban/mute/deleteMessage） |
| GM-02 | 禁言/解除禁言（定时） | C | 端口 `mute` 可执行；**定时解除缺调度器**（`MuteSchedule` 为零引用孤儿且自述"由调度侧触发"） |
| GM-03 | 警告/清除警告（累计） | A | `WarningOrchestrator` + `WarningPolicy` + `WarningPort` + `ModerationCommandHandler`；权限缺口已修（入口 `requireAdmin`） |
| GM-04 | 群主自主封禁（独立 API） | E | 未读到决定它的实现 |
| GM-05 | 管理员配置（添加/移除） | B | `AdminRegistry` 存在但**零生产调用**；实际生效的是实时角色解析 `MemberRolePort`/`TelegramMemberRoleAdapter` |
| GM-06 | 违禁词过滤 | A | `BannedWordMatcher` + `BannedWordStore` + `JpaModerationAdapters` + `AdminWordController`（在线管理入口） |
| GM-07 | 反刷屏（Redis 滑动窗口） | C | `SlidingWindowCounter` + `RateLimiter`；**非 Redis**（进程内） |
| GM-08 | 相同消息 3 次判定 | A | `MessageRepeatDetector`，经 `MessageGuardService` 挂入消息管道 |
| GM-09 | 消息自动删除 | A | `GroupAdminPort.deleteMessage` + `MessageGuardOrchestrator` 的删除动作 |
| GM-10 | 链接过滤（白名单+短链） | A | `LinkFilter` + `LinkMatch`（已修"空白名单误删带链接消息"） |
| GM-11 | 媒体过滤（类型白名单） | A | `MediaFilter`（只对有 fileInfo 的消息判定） |
| GM-12 | 频道傀儡攻击防御 | B | `ChannelPuppetGuard` 存在但零生产调用 |
| GM-13 | 定期防钓鱼推送 | C | `PhishingNotice`；"定期"缺调度器 |
| GM-14 | 欢迎新成员 | A | `WelcomeTemplate` + `MemberJoinHandler`，挂在入群路径 |
| GM-15 | 入群验证（策略化+60s 超时踢出） | C | `JoinVerificationFlow`；**超时依赖调度器**；Mini App 交互在 `TradeApiController` 侧另有实现 |
| GM-16 | 频道订阅前置验证 | A | `ChannelSubscriptionCheck` + `ChannelMembershipPort` + `JoinSubscriptionGate` + `TelegramChannelMembershipAdapter`（**本会话接线**；默认不启用） |
| GM-17 | 定时任务 + 自动回复 | C | `KeywordAutoReply`；调度器缺 |
| GM-18 | 静默模式（时段配置） | A | `SilenceWindow` + `NoticePolicy`（**本会话经 `TradeNotifier` 接线**） |
| GM-19 | 功能开关（按群组） | B | `FeatureToggle` 零生产调用（其 fail-closed 对保护类功能是反向的，接线前须先定可选功能清单） |
| GM-20 | 日志频道（操作推送） | D | 未找到实现类 |
| GM-21 | 群组信息同步（定期） | D | 未找到实现类 |
| GM-22 | 滑动窗口检测（1 分钟 >10 人触发） | A | `SlidingWindowCounter` + `JoinBurstGuard`（**本会话把"由滑动窗口触发"从文档变成实现**） |
| GM-23 | 保护模式（暂时拒绝新申请） | A | `ProtectionMode` + `JoinBurstGuard`（自动开/惰性解除）+ `MemberJoinHandler`（**真的移出**，不再只发提示） |
| GM-24 | 风险评分 | C | `JoinRiskScorer` + `JoinSignals` + `RiskFactor`（评分件在；接入入群门禁的深度未核实） |
| GM-25 | 高分拒绝 | C | `RiskPolicy` + `RiskAssessment`（同上） |
| GM-26 | 第二渠道验证 | C | `SecondaryVerificationFlow` |
| GM-27 | 账号异常检测 | C | `AnomalyDetector` |
| GM-28 | Bot 防拉群机制 | E | 未读到决定它的实现 |
| GM-29 | 年龄确认（COPPA） | D | 未找到实现类（待决 B4） |
| GM-30 | 未成年人保护 | D | 未找到实现类（待决 B4） |
| GM-31 | 权限系统（角色+校验） | A | `PermissionChecker` + `PermissionPolicy` + `MemberRole` + `CommandActor` + `MemberRolePort`（实时解析 creator/administrator） |
| GM-32 | 三级限流（用户/群组/全局） | C | `RateLimiter` + `RateLimitPolicy` + `RateLimitDecision`；接入深度未核实 |
| GM-33 | 日志脱敏（Token + 用户 ID） | C | `LogSanitizer`；**哈希无盐且仅 24bit（SPEC 6.2 缺陷未修）** |
| GM-34 | 数据备份（MySQL + Redis） | D | 未找到实现类（属运维动作） |
| GM-35 | 用户首次使用引导 | B | `JoinOnboarding` 零生产调用（缺"已引导过"的持久化） |
| GM-36 | 通知三维度（可见范围/推送/留存） | A | `NoticePolicy` + `TradeEvent` + `TradeNotifier`（**本会话接线**；`EPHEMERAL` 维度未映射——当前事件全为 PERMANENT） |

### 2.2 担保交易（ET 01–80）

| 编号 | 功能 | 判定 | 证据（实测） |
|---|---|---|---|
| ET-01 | 交易入口 | **A** | `TradeCommandHandler` + `BotWiring`/`BotDispatcher`（**旧标"Bot 接线待 S1"已过时**）+ `TradeApiController`/`TradeInviteController` |
| ET-02 | 交易状态查询 | A | `TradeStatusView` |
| ET-03 | 信用分联动 | C | `CreditScore`（联动入交易准入的深度未核实） |
| ET-04 | 与群管理联动（欺诈触发封禁） | C | `FraudLinkage` |
| ET-05 | 交易群创建引导 | C | `TradeGroupLifecycle` + `EscrowGroupGuide` |
| ET-06 | 交易群机器人公告 | C | `TradeMessageMapPort` |
| ET-07 | Web 交易界面 | E | 未读到决定它的实现（`web/` 目录本会话只见 README） |
| ET-08 | Telegram OAuth（ton_proof） | A | `WebAppInitDataVerifier`（`initData` 验签 + `max-age`） |
| ET-09 | TON USDT 支付 | D | 未找到实现类（S5 未部署） |
| ET-10 | TON 合约托管（Tolk） | C | `contracts/EscrowContract.tolk` **仅可编译骨架**；Acton 工具链已接，资金逻辑未写、未部署 |
| ET-11 | 资金冻结/释放/退款 | C | `EscrowTradeService.lock/release/refund`——**仅状态登记**，链上未接 |
| ET-12 | 交易状态机 | A | `EscrowOrder`（8 态 + fail-closed 守卫） |
| ET-13 | 超时自动处理 | C | `TradeTimeoutPolicy`（惰性判定，无调度器；"自动"限于命令触发时结算） |
| ET-14 | 争议发起与冻结 | A | `DisputeFlow` |
| ET-15 | 多签仲裁（2/3 必含联邦） | C | `EscrowMultiSigRule` + `PartySignatureVerifier` + `FederationPartySignatureVerifier`（Java 侧；**链上未落，语义注意 SPEC 6.4**） |
| ET-16 | 多签规则校验 | A | `EscrowVerdictGuard` + `EscrowVerdict` |
| ET-17 | 证据提交（IPFS） | C | `DeliveryProofSubmission` + `EvidenceHasher`；**IPFS 未接** |
| ET-18 | 交易评价（双方互评） | A | `TradeReview` + `TradeReviewService` + `JpaTradeReviewStore`（本会话修过 `@Transactional` 装配缺陷） |
| ET-19 | 链上事件监听 | C | `BalanceCrossVerifier` + `ChainSource`；**无真实链上源** |
| ET-20 | 合约审计（第三方） | D | 未找到实现类（属外部动作） |
| ET-21 | 时间锁 + 紧急暂停 | D | 未找到实现类（合约侧） |
| ET-22 | KYC 最小化 + 加密 | D | 未找到实现类（待决 B4） |
| ET-23 | 单笔限制 | A | `TradeAdmissionGate` + `TradeAdmissionPolicy` |
| ET-24 | 单笔冷却期（24h） | A | 同上 |
| ET-25 | 争议期间冻结 | A | 同上（优先级高于冷却期） |
| ET-26 | 维护时间选择（5 选项） | A | `MaintenanceWindow` |
| ET-27 | 超时自动确认 | A | `TradeTimeoutPolicy`（同 ET-13） |
| ET-28 | 维护期争议暂停 | A | `DisputeFlow.pausedRemaining` |
| ET-29 | 平台费自动划转 | D | 未找到实现类（待决 B1） |
| ET-30 | 联邦管理员费用划转 | D | 未找到实现类（待决 B1） |
| ET-31 | TON Pay 集成 | D | 未找到实现类 |
| ET-32 | TON Pay Webhook 验证 | D | 未找到实现类 |
| ET-33 | 交易金额分层验证 | **A** | `AmountTierPolicy` + `RiskPrompt`（随建单响应回传；**旧标 ⬜ 属漏标**） |
| ET-34 | 交易前风险提示 | **A** | `RiskPrompt` + `PendingTradeRegistry`（两步流：create 登记预览 → confirm 校验同买方/参数/未过期后一次性消费；commit `ef01ccf`；**旧标 ⬜ 属漏标**） |
| ET-35 | 首单保障 | C | `NewcomerBadge`（待决 B2） |
| ET-36 | 主动推送 | **A** | `TradeNotifier` + `TradeEvent` + `NotificationOutcome` + `NoticePolicy`（**本会话新增**；对手方通知，fail-soft） |
| ET-37 | 交易信用可视化 | C | `CreditScore` + `TraderTier` |
| ET-38 | 交易信用冷启动（新手标识） | C | `NewcomerBadge` |
| ET-39 | 交易群生命周期（7 天归档） | C | `TradeGroupLifecycle`（归档依赖调度器） |
| ET-40 | 交易群静默期 | C | `SilenceWindow` + `NoticePolicy` |
| ET-41 | 群成员投票 | C | `MemberVote` + `VoterRegistry` + `VoteRewardAllocator` |
| ET-42 | 投票人回避机制 | C | `VoterRecusal` |
| ET-43 | 争议证据双通道保存 | C | `EvidenceChannel` |
| ET-44 | 投票奖励分配 | C | `VoteRewardAllocator` |
| ET-45 | 证据时效性（24h） | C | `EvidenceDeadline` |
| ET-46 | 仲裁员加入费动态门槛 | C | `StakeThresholdAdjuster` + `VoteStake` |
| ET-47 | 投票截止后 FallbackSettle 兜底 | C | `FallbackSettle` |
| ET-48 | 重放保护（seqno/nonce） | B | `ReplayGuard` 零生产调用（**缺 nonce 源**：唯一签名面 `initData` 在同一会话内被重复携带，按 nonce 拒会打断正常使用） |
| ET-49 | 交易阶段标注 | A | `TradeStage` |
| ET-50 | Fake Jetton 验证 | D | 未找到实现类（合约侧） |
| ET-51 | 链上存证 | C | `EvidenceHasher`；链上未接 |
| ET-52 | x402 Proof Hash | C | 概念落在 `deliveryProofHash`（不绑 x402，见 SPEC 6.5） |
| ET-53 | 交付证明上链 | C | 同 ET-51 |
| ET-54 | 防钓鱼提示 | C | `PhishingNotice` |
| ET-55 | Permit Signature 钓鱼防御 | C | SPEC 6.3 定为**不制造授权面**（设计层面的规避，非独立实现） |
| ET-56 | 代理合约可升级性 | D | 未做（默认关闭，待决 C2） |
| ET-57 | 预映像攻击防护（哈希前加盐） | C | `EvidenceHasher`/`LogSanitizer`；**加盐未做（SPEC 6.2）** |
| ET-58 | TON 异步消息顺序处理 | C | Java 侧守卫已有（`EscrowOrder` 前置条件）；合约侧待做 |
| ET-59 | 资金流向链上验证 | C | `BalanceCrossVerifier`（无真实源） |
| ET-60 | 争议双方陈述 | C | `DisputeStatementFlow` |
| ET-61 | 多维度惩罚 | C | `PenaltyPlanner` |
| ET-62 | 社区氛围建设（周榜/月报） | C | `Leaderboard` |
| ET-64 | 设备关联检测 | C | `DeviceLinkAnalyzer` |
| ET-65 | 互刷模式检测 | C | `MutualBrushDetector` |
| ET-66 | 新账号交易限制 | C | `NewAccountLimitPolicy` |
| ET-67 | 互刷不累加信用 | C | `MutualBrushDetector`（"不累加"的落点未核实） |
| ET-68 | 可疑交易关系标记 | C | `SuspiciousRelationPolicy` |
| ET-69 | Comment 钓鱼提示 | C | `PhishingNotice` |
| ET-70 | 官方不通过 comment 发奖励 | E | 属行为约定，未读到决定它的实现 |
| ET-71 | Permit Signature 警示 | C | 同 ET-54/ET-55 |
| ET-72 | 交易对手多样性评分 | C | `CounterpartyDiversityScorer` |
| ET-73 | 账号异常检测 | C | `AnomalyDetector` |
| ET-75 | 交易信用综合评分 | C | `CreditScore`（**待决 B3**：等级条件冲突等 5 点未澄清） |
| ET-76 | 交易者等级评定 | C | `TraderTier` |
| ET-77 | 周榜推送 | C | `Leaderboard` + `TradeNotifier`（推送通道已在；定时缺调度器） |
| ET-78 | 月榜推送 | C | 同上 |
| ET-79 | 季度荣誉榜 | C | `Leaderboard` |
| ET-80 | 排名隐私控制 | C | `RankPrivacy` |

### 2.3 计数

| 判定 | 群管理 | 担保交易 | 合计 |
|---|---|---|---|
| **A｜已落地·已接线** | 13 | 17 | **30** |
| **B｜已落地·未接线** | 4 | 1 | **5** |
| **C｜部分实现** | 12 | 48 | **60** |
| **D｜未找到实现类** | 5 | 10 | **15** |
| **E｜未核实** | 2 | 2 | **4** |
| 合计 | 36 | 78 | **114** |

> 本表由 `awk` 从上方逐项表实测汇总（`awk -F'|' '/^\| (GM|ET)-[0-9]+/{print $4}'`），不是手写估计——
> 初稿我凭手感填过一次，与实测差得很大，已按实测更正。

## 3. 支撑类（不占功能编号，实测存在）

- **持久化**：`EscrowOrderRepository` / `JpaEscrowOrderStore` / `JpaTradeHistoryPort` / `JpaTradeInviteStore` / `JpaTradeReviewStore` / `JpaModerationAdapters` / `TradeInviteRepository`
- **端口与适配器**：`GroupAdminPort`(+`TelegramGroupAdminAdapter`)、`MemberRolePort`(+`TelegramMemberRoleAdapter`)、`ChannelMembershipPort`(+`TelegramChannelMembershipAdapter`)、`BotReplyPort`(+`TelegramBotReplyAdapter`)、`TradeMessageMapPort`、`UserPreferencePort`、`WarningPort`
- **命令与会话**：`CommandParser` / `BotCommand` / `TradeEvent` / `NotificationOutcome`
- **联邦与链上**：`FederationKeyPair` / `BalanceCrossVerifier` / `ChainSource` / `VerifiedBalance`
- **交易服务与准入**：`EscrowTradeService` / `TradeInviteService` / `TradeMaintenanceService` / `TradeReviewService` / `TradeAdmissionGate` / `PendingTradeRegistry` / `RiskPrompt`
- **合约工程（S5）**：`Acton.toml` + `.acton/` + `wrappers/EscrowContract.gen.tolk` + `tests/EscrowContract.test.tolk`（Acton v1.2.0，`acton build`/`acton test` 通过）

## 4. 结论

**一句**：逐项实测 114 行（GM 36 + ET 78；其中 GM-36 取 SPEC 6.6 节的定义，SPEC 状态表本身缺该行），
**已落地 35 条（30 已接线 + 5 未接线）**、**部分实现 60 条**、未找到实现 15 条、未核实 4 条。
与旧快照的"已落地 31 / 未做 84"相比，**不是功能变多了、而是判定标准变了**：旧版把"有类"就记 ✅（所以出现
`SilenceWindow` 这类零引用孤儿被算作已完成），本版把"**是否接线、用户能否用到**"单独拆出来。

**三条最关键的差距**（更正旧快照）：
1. ~~S1 无 Bot 接线~~ → **已不是缺口**；`/escrow` 命令链与 Mini App 接口都通。
2. ~~S6 无 Web 层~~ → **已不是缺口**；`TradeApiController`/`TradeInviteController` + `initData` 验签在位。
3. **S5 合约仍是真缺口** → 托管/仲裁/存证的资金面全部停在"状态登记"，且 `EscrowContract.tolk` 只有骨架。
   这是当前**唯一**横跨大量 ET 项的结构性阻塞（ET-09/10/11/15/17/19/21/50/51/53/59 等）。

**已知质量缺口（非规格缺项）**：
- `LogSanitizer` 无盐哈希 + 仅 24bit（SPEC 6.2 要求加部署级盐值 + 加长输出）——**未修**；
- `BannedWordMatcher` 逐词 `contains`（无 Trie，词表大时有压力）；
- 五个"已落地未接线"的类（`AdminRegistry`/`ChannelPuppetGuard`/`FeatureToggle`/`JoinOnboarding`/`ReplayGuard`）——
  每个都卡在缺件上（详见 `.rivet/HANDOFF.md` 的记录），**不是补一行调用能收口的**。

**验证状态**：代码面 `mvn verify` 805 + 4 IT 全绿；**真机面未核销**（`docs/ONLINE-VERIFICATION.md` 的 A9/A10/A11，
障碍是缺 `TELEGRAM_BOT_TOKEN`），因此"用户可用"目前**只有本地证据、没有真机证据**。
