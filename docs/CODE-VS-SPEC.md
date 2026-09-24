# 代码 vs 规格 差距对比

> 对照基准：`docs/requirements/SPEC.md`（115 项，编号 `GM/ET`）
> 代码实测：`HEAD=91c5642`，**65 个 main 类 / 37 个 test 类**，`mvn test` **314 项全绿**
> 测量方式：`find`+`grep` 逐类实测（非凭文档转述）。**本文档是特定时点的快照，随开发变化。**

## 1. 总体差距

| 维度 | 数量 |
|---|---|
| 规格功能总数 | **115**（群管理 35 + 担保交易 80） |
| **已落地** | **31 项**（群管理 16 + 担保交易 15）= **27%** |
| **未做** | **84 项** = **73%** |
| 另有支撑类（端口/异常/解析/持久化等，不占功能编号） | 约 20 个类 |

**分模块**：

| 模块 | 已落地 | 未做 | 完成度 |
|---|---|---|---|
| 群管理（GM 01–36） | 16 | 20 | **44%** |
| 担保交易（ET 01–80） | 15 | 65 | **19%** |

> 担保交易完成度显著低于群管理——因为其大部分（Web 平台、TON 合约、投票、排名、防黑产）
> 依赖尚未立项的 **S6（Web 层）** 与 **S5（合约）**。

## 2. 已落地 31 项（逐项实测：功能 → 实现类）

### 群管理（16 项）
| 编号 | 功能 | 实现类（实测存在） |
|---|---|---|
| GM-05 | 管理员配置 | `AdminRegistry` |
| GM-06 | 违禁词过滤 | `BannedWordMatcher` |
| GM-08 | 相同消息 3 次判定 | `MessageRepeatDetector` |
| GM-10 | 链接过滤 | `LinkFilter` + `LinkMatch` |
| GM-11 | 媒体过滤 | `MediaFilter` |
| GM-14 | 欢迎新成员 | `WelcomeTemplate` |
| GM-15 | 入群验证（含超时） | `JoinVerificationFlow` |
| GM-18 | 静默模式 | `SilenceWindow` |
| GM-19 | 功能开关 | `FeatureToggle` |
| GM-22 | 滑动窗口检测 | `SlidingWindowCounter` |
| GM-23 | 保护模式 | `ProtectionMode` |
| GM-24 | 风险评分 | `JoinRiskScorer` + `JoinSignals` + `RiskFactor` |
| GM-25 | 高分拒绝 | `RiskPolicy` + `RiskAssessment` |
| GM-31 | 权限系统 | `PermissionChecker` + `PermissionPolicy` + `MemberRole` + `CommandActor` |
| GM-32 | 三级限流 | `RateLimiter` + `RateLimitPolicy` + `RateLimitDecision` |
| GM-33 | 日志脱敏 | `LogSanitizer` ⚠️（哈希无盐/24bit，见 SPEC 6.2） |

### 担保交易（15 项）
| 编号 | 功能 | 实现类（实测存在） |
|---|---|---|
| ET-01 | 交易入口 | `TradeCommandHandler`（**仅命令层**，Bot 接线待 S1） |
| ET-02 | 交易状态查询 | `TradeStatusView` |
| ET-12 | 交易状态机 | `EscrowOrder`（8 态 + fail-closed 守卫） |
| ET-13 | 超时自动处理 | `TradeTimeoutPolicy` |
| ET-14 | 争议发起与冻结 | `DisputeFlow` |
| ET-15 | 多签仲裁 | `EscrowMultiSigRule` + `PartySignatureVerifier` + `FederationPartySignatureVerifier` |
| ET-16 | 多签规则校验 | `EscrowVerdictGuard` + `EscrowVerdict` |
| ET-18 | 交易评价 | `TradeReview` |
| ET-23 | 单笔限制 | `TradeAdmissionGate` + `TradeAdmissionPolicy` |
| ET-24 | 单笔冷却期 | `TradeAdmissionGate`（同上） |
| ET-25 | 争议期间冻结 | `TradeAdmissionGate`（同上） |
| ET-26 | 维护时间选择 | `MaintenanceWindow` |
| ET-27 | 超时自动确认 | `TradeTimeoutPolicy`（同 ET-13） |
| ET-28 | 维护期争议暂停 | `DisputeFlow.pausedRemaining` |
| ET-49 | 交易阶段标注 | `TradeStage` |

### 支撑类（不占功能编号，但已落地）
- **持久化地基**：`EscrowOrderRepository` / `JpaEscrowOrderStore` / `JpaTradeHistoryPort` + `EscrowOrderStore` / `TradeHistoryPort` 端口
- **命令解析**：`CommandParser` / `BotCommand`
- **联邦签名**：`FederationKeyPair` / `FederationException`
- **链上双源交叉验证**：`BalanceCrossVerifier` / `ChainSource` / `VerifiedBalance` / `BalanceObservation`（+ 3 个异常类）
- **交易创建服务**：`EscrowTradeService` + `TradeInitiationRequest` / `TradeInitiationResult`

## 3. **部分实现**的项（易被误记为"已完成"）

| 编号 | 已做的部分 | **缺的部分** |
|---|---|---|
| **ET-01** 交易入口 | 命令解析 + 服务调用 + 回执（`TradeCommandHandler`） | **Bot 接线**（S1）——用户实际发不出命令 |
| **ET-15** 多签仲裁 | 签名规则与校验（Java 侧） | **链上合约**（S5）——规则未落到 TON |
| **ET-19 / ET-59** 链上事件/资金验证 | 双源交叉验证器（`BalanceCrossVerifier`） | **真实链上源**（S4 `HttpChainSource`）——无真实数据 |
| **GM-15** 入群验证 | 状态机与超时守卫 | **Mini App 问答交互**（依赖 S1） |

> ⚠️ 这四项若按"有类即算完成"统计会虚高。**实际用户可用性为零**（无 Bot 接线、无链上、无 Web）。

## 4. 未做的 84 项（按依赖分组）

| 组 | 编号 | 数量 | 阻塞 |
|---|---|---|---|
| 群管理·需 Telegram API 端口 | GM-01 GM-02 GM-04 GM-09 GM-20 GM-21 GM-28 | 7 | 需 `GroupAdminPort` + S1 |
| 群管理·需持久化/调度 | GM-03（警告累计） GM-07（Redis） GM-17（调度） GM-34（备份） | 4 | V2 迁移 / Redis / 调度器 |
| 群管理·纯逻辑但未做 | GM-12 GM-13 GM-16 GM-26 GM-27 GM-35 GM-36 | 7 | 零依赖（**可立即开工**） |
| 群管理·合规 | GM-29 GM-30 | 2 | 待决 B4 |
| 交易·Web 平台 | ET-03~11 ET-17 ET-19~22 ET-29~38 | 约 30 | **S6（Web 层）+ S5（合约）+ A2 token** |
| 交易·留痕与投票 | ET-39~47 | 9 | Wave 1b（纯逻辑，可开工） |
| 交易·安全增强 | ET-48 ET-50~58 | 约 11 | 多数需 S5 合约 |
| 交易·人性化 | ET-60~62 | 3 | 依赖推送/信用分 |
| 交易·防黑产 | ET-64~74 | 11 | 部分可立即（纯逻辑） |
| 交易·排名 | ET-75~80 | 6 | 待决 B3（模型 5 点待澄清）+ 聚合查询 |

## 5. 结论

**一句**：规格 115 项，代码落地 **31 项（27%）**；且其中 4 项仅为**部分实现**，
真正**用户可用**的功能为 **0**——因为无 Bot 接线（S1）、无 Web 层（S6）、无链上合约（S5）。

**三条最关键的差距**（都是"结构性缺口"而非单点功能）：
1. **S1 无 Bot 接线** → 全部功能对用户不可见（需 A2 token）；
2. **S6 无 Web 层** → 卡住约 30 项交易功能（TON Pay webhook、交易界面、管理后台、Mini App）；
3. **S5 无 TON 合约** → 卡住托管/仲裁/存证等资金安全核心（需先定 C1 多签语义）。

**可立即推进而不受上述阻塞的**（纯逻辑，零外部依赖）：
群管理 GM-12/13/16/26/27/35/36（7 项）+ 交易 ET-39~47（9 项）+ 部分防黑产（ET-64/65/67/72 等）。
这批正是计划中的 **Wave 1b / 1c**。

**代码侧已知待改进**（非规格缺项，是质量问题）：
- `LogSanitizer` 哈希无盐 + 仅 24 bit（SPEC 6.2）；
- `BannedWordMatcher` 逐词 `contains`（词表规模大时有性能压力，且有在线管理需求）；
- Wave 1b 规格需按 SPEC 第 5 节修订（交易群绑定交易 ID、投票人分层+观察员）。
