# 代码 vs 规格 差距对比（**实测重建版**）

> **⚠️ 先看这段：§2 的逐项表已于 2026-09-29 按「引用 + 注解」机械复核（重测记录见 §6）。**
> 复核口径：对每行引用的实现类，实测「生产侧非注释引用数」与「是否带装配注解」（脚本见 §5 第 3 条）。
> 本轮据此**修正了 3 处过期判定**（GM-08、GM-33 由 B→A；ET-50 由 D→C），并**用命令从正文重算了 §2.3**。
> **C 级 60 行亦已完成逐行回读**（五批，见 §6.2）——判定变更 1 行（GM-32 C→A），其余描述落到实测。
> 仍然成立的限制：回读确认的是「类是否存在、被谁引用、缺件是什么」，**不等于**做过行为级验证。
> 引用任何一行之前，仍请按 §5 的命令复算那一行。"文档说 A、代码是 B"正是本文档栽过的坑。
>
> **本波（信用/榜单读模型接线 Wave 1–3）**：ET 侧 **9 行 C→A**（ET-03 / 37 / 62 / 72 / 75 / 76 / 77 / 78 / 79），
> 依据是两条命令接上了真实数据——`/escrow rank`（榜单）与 `/escrow credit`（个人摘要），
> 逐行证据见 §2.2。**注意证据强度**：只到**分发器层**（本地 `BotDispatcherTest`），
> 真实群里的可见性与投递仍未核销（同 §4「验证状态」）。

> **本文档取代 `HEAD=91c5642` 时点的旧快照。** 旧快照有两重过时：
> ① 当时就漏标（如 SPEC 标 ET-34 ⬜ 而代码早已落地 `RiskPrompt` + `PendingTradeRegistry`）；
> ② 此后又新增大量实现类（实测 main 类数已是旧快照的 2.5 倍）。
>
> **对照基准**：`docs/requirements/SPEC.md`（其状态表共 113 行；本文档逐项 114 行 = GM 36 + ET 78，
> 多出的 GM-36 取自 SPEC 6.6 节的定义——SPEC 状态表本身漏了该行）
> **代码实测（2026-09-29 · 三波接线之后：信用/榜单 + 频道傀儡 + 入群风控）**：**main 181 类 / test 124 类**、
> `mvn verify` **932 用例 + 4 个 IT 全绿**（模块分布 293 / 13 / 372 / 40 / 214，另 4 个 IT）。
> 本波（入群风控）新增 16 条：`JoinSignalsTest` 4（新类）、`JoinRiskScorerTest` +5、`MemberJoinHandlerTest` +5、
> `TelegramBotHandlerTest` +2；此前两波的新增（9 + 16）见各自提交。
> **本波前** HEAD=`89701cf`（三波的提交依次为 `b3ac31a`/`f019d07`+`859aab9`/`7a670a3`、`4fb4f8a`+`89701cf`；本波见 `git log -1`）。
> **上一版记录（留作对照）**：`HEAD=6e7edb6`（同类数、同用例数，即本轮复核与它一致）· 更早 `HEAD=14bbf6d`、main 164 类 / test 107 类、805 用例 + 4 IT
> **测量方式**：`find`/`grep`/`awk` 逐类实测，非文档转述；复算命令见文末 §5。
>
> **2026-10-03 清理后实测**（「项目全面清理」计划，方案一）：**main 227 类 / test 159 类**；
> 删除 16 个「不接/冲突」类及各自测试（8 个 Wave 2 + 投票簇 8 个，git 历史可找回）；
> 归档旧文档（`docs/requirements/archive/`、`docs/archive/plans/`）。用例数与验证结果见 §4。
> **2026-10-03 注释精简轮**：一批 javadoc 被压缩（端口/接口类与装配类），文中 `file:line`
> 锚点<b>再次整体漂移</b>——任何锚点引用前请先 `grep -n` 实测，本文不再逐一跟踪。
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
| 无 TON 合约（S5） | ✅ 仍成立（**未部署**）。`contracts/EscrowContract.tolk` 已非骨架：受守卫状态机 + 非裁决资金路径（fund/deliver/confirm/refund）+ 回弹安全网 + **Jetton 计价托管（入金 + 出账）** + Jetton 发送者校验（ET-50）均已实现并有 **47** 条用例（`acton test` 实测，2026-09-30；2026-09-29 为 26，随升级入口增至 47）；**真缺口只剩**争议/多签裁决与存储费池额度，且**链上部署与真机验证均未做**。**更正**：原文"jetton 记账/出账未做（资产类型未记录）"与合约源码冲突——`EscrowExtra.asset` 明确记录计价资产，出账路径亦已实现且有用例覆盖 |

**但这不等于"可用"已达预期**：真机路径未核销（`docs/ONLINE-VERIFICATION.md` 的 A9/A10/A11），
且链上托管（ET-09/10/11 的资金面）仍是空的——现在的"资金"只是**状态登记**。

## 2. 逐项实测（114 条：GM 36 + ET 78）

> **编号说明**：ET 序列里**没有 ET-63 与 ET-74**——不是漏行，是 SPEC 自己把它们去重了
> （ET-63 与 ET-60 完全重复 → 删除；ET-74 与 GM-13 重复 → 合并，见 `docs/requirements/SPEC.md`
> 第 2 行与文末「重复纠正」）。故 ET 表实为 78 行；早先的表头写「113 条」是错的，已更正。

### 2.1 群管理（GM 01–36）

> ⚠️ **2026-10-03**：GM 全族**已从代码整体移除**（用户决策：只保留担保交易）。下表保留供审计
> （历史判定与证据仍有效——GM 行的「已落地」是**当时**的事实）；移除批次记录见
> `.rivet/HANDOFF.md` 第 17 条。交易群（ET-05/06/39/40）与群内 `/escrow` 命令**保留**。

| 编号 | 功能 | 判定 | 证据（实测） |
|---|---|---|---|
| GM-01 | 踢出/封禁/解封 | **A** | `GroupAdminPort`（实现 `TelegramGroupAdminAdapter`、命令入口 `ModerationCommandHandler`，均已接线）现对外含 kick / ban / mute / deleteMessage **+ unban / unmute**；解封入口 `/unban <用户ID>`、解除禁言 `/unmute <用户ID>` 已接线（`ModerationCommandHandler:166,170` → `ModerationOrchestrator.unban/unmute:95,101` → `TelegramGroupAdminAdapter.unban/unmute:120,129`，d5198f2）。**本次回写**：原记「实测端口无 unban → 确无实现」已过时——现已接线 |
| GM-02 | 禁言/解除禁言（定时） | **A** | 分层回写（本次）：① **手动解除已落地**——`/unmute <用户ID>`（`ModerationCommandHandler:170` → `ModerationOrchestrator.unmute:101` → `TelegramGroupAdminAdapter.unmute:129` 以 `untilDate=0` + 全开权限解除，d5198f2）；② **定时解除由 Telegram 服务端保证**——禁言即以 `untilDate`（unix 秒，`TelegramGroupAdminAdapter:101,104`）下发，到期服务端自动解除，无需本地调度器（与项目「无调度器」非目标一致）；③ `MuteSchedule`（审计视图：本地方便核对禁言台账）**已删除**（2026-10-03 清理，决策 §7.1.1「维持不接」，git 可找回）。**本次回写**：原记「解除属真需定时、无实现」已由 ① ② 两层覆盖 |
| GM-03 | 警告/清除警告（累计） | A | `WarningOrchestrator` + `WarningPolicy` + `WarningPort` + `ModerationCommandHandler`；权限缺口已修（入口 `requireAdmin`） |
| GM-04 | 群主自主封禁（独立 API） | E | 未读到决定它的实现 |
| GM-05 | 管理员配置（添加/移除） | **X** | `AdminRegistry` 已删除（2026-10-03 清理，决策 §7「不接」——与 `MemberRolePort`/`TelegramMemberRoleAdapter` 职责重叠，git 可找回）；实际生效的是实时角色解析 `MemberRolePort`/`TelegramMemberRoleAdapter` |
| GM-06 | 违禁词过滤 | A | `BannedWordMatcher` + `BannedWordStore` + `JpaModerationAdapters` + `AdminWordController`（在线管理入口） |
| GM-07 | 反刷屏（Redis 滑动窗口） | C | `SlidingWindowCounter` + `RateLimiter`（**进程内**实现）；**实测全仓 `pom.xml` 无 Redis 依赖**，故"非 Redis"属实。触发语义是**严格超过**阈值（与 GM-08 的"达到即算"不同） |
| GM-08 | 相同消息 3 次判定 | **A** | `MessageRepeatDetector` 已进 `MessageGuardOrchestrator` 判定链（字段 `:86`、构造注入 `:96`），由 `BotWiring:748` 按 `tgg.guard.repeat.threshold` 装配（**默认 0 = 不启用**，不改既有行为）。**本次重测的更正项**：本行曾记 B（"仅被自己的测试引用"），现已接线 |
| GM-09 | 消息自动删除 | A | `GroupAdminPort.deleteMessage` + `MessageGuardOrchestrator` 的删除动作 |
| GM-10 | 链接过滤（白名单+短链） | A | `LinkFilter` + `LinkMatch`（已修"空白名单误删带链接消息"） |
| GM-11 | 媒体过滤（类型白名单） | A | `MediaFilter`（只对有 fileInfo 的消息判定） |
| GM-12 | 频道傀儡攻击防御 | **A** | 已接进内容安全判定链的**首位**（`MessageGuardOrchestrator` 新增可空 `ChannelPuppetGuard`，在 `inspect` 中排在链接之前——身份伪造比内容可疑更根本）；装配 `BotWiring.messageGuardOrchestrator` → `channelGuardOrNull`，配置键 `TGG_GUARD_ALLOWED_CHANNELS`：**未配置 = 不参与该判定**（默认行为不变）、**配成空串 = 全拦**、配具体频道名 = 仅放行这些。**前置缺口同波修复**：频道帖此前因 `userId=0` 在 `IncomingMessage` 构造期被拒 → 整条消息（含内容安全与命令）不被处理；现由 `senderIsChannel`/`senderName` 承载（`TelegramBotHandler.toIncoming` 读 `senderChat`），且频道帖**不路由业务命令**（无个人主体，避免凭空造出 0 号用户）、处置时**不累计个人警告**。**本次重测的更正项**：原记 B（"存在但零生产调用"） |
| GM-13 | 定期防钓鱼推送 | **A** | `PhishingNotice` **已接线**（2026-10-01）——原记「无生产调用点」**已过时**：`WeeklyBoardPush.compose` 消费 `noticeOfWeek(周序号)`，随周榜惰性推送每周每群送达一条（不引入调度器）；空榜时**只发提醒**（防钓鱼的价值恰在没出事时）。合并定义库覆盖 GM-13 / ET-54 / ET-69 / ET-71 四个编号 |
| GM-14 | 欢迎新成员 | A | `WelcomeTemplate` + `MemberJoinHandler`，挂在入群路径 |
| GM-15 | 入群验证（策略化+60s 超时踢出） | **X** | `JoinVerificationFlow` 已删除（2026-10-03 清理，决策 §7.1「不接」——60s 超时属"真需定时"，即时验证已由 `JoinSubscriptionGate` 覆盖，git 可找回） |
| GM-16 | 频道订阅前置验证 | A | `ChannelSubscriptionCheck` + `ChannelMembershipPort` + `JoinSubscriptionGate` + `TelegramChannelMembershipAdapter`（**本会话接线**；默认不启用） |
| GM-17 | 定时任务 + 自动回复 | C | **自动回复已接线**（`BotDispatcher` 持有 `KeywordAutoReply`，实测真实字段注入）；"定时任务"部分属"真需定时"，按项目非目标降级 |
| GM-18 | 静默模式（时段配置） | A | `SilenceWindow` + `NoticePolicy`（**本会话经 `TradeNotifier` 接线**） |
| GM-19 | 功能开关（按群组） | **X** | `FeatureToggle` 已删除（2026-10-03 清理，决策 §7「不接（待产品决策）」——其 fail-closed 对保护类功能是反向的，接线前须先定可选功能清单，git 可找回） |
| GM-20 | 日志频道（操作推送） | **A** | **A｜已落地·已接线（2026-10-03）**：`ModerationAuditPort`（core 契约：实现方不留痕不抛、只在动作成功后调用）+ `ModerationOrchestrator` 各动作成功后审计（守卫拒绝/端口失败**一律零留痕**——记录必须对应真实发生的动作）+ `TelegramModerationAuditAdapter`（推私密审计频道；`tgg.moderation.log-channel-id=0` 未配置不推；推送失败不抛只 warn）+ `GuildWiring` 装配。测试：`ModerationOrchestratorTest` 4 审计用例 + `TelegramModerationAuditAdapterTest` 6 用例。真机核销见 ONLINE-VERIFICATION **A23** |
| GM-21 | 群组信息同步（定期） | **A** | **A｜已落地·已接线（2026-10-03）**：`GroupTitlePort` + `TelegramGroupTitleCache`（getChat + TTL 10 分钟 + 失败降级不抛；「定期同步」以**按需 TTL** 形态落地——无调度器风格）+ 消费方已接（审计留痕文案带群名，查不到回退裸 ID）+ `GuildWiring` 装配。测试：`TelegramGroupTitleCacheTest` 6 用例。真机核销见 ONLINE-VERIFICATION **A23** |
| GM-22 | 滑动窗口检测（1 分钟 >10 人触发） | A | `SlidingWindowCounter` + `JoinBurstGuard`（**本会话把"由滑动窗口触发"从文档变成实现**） |
| GM-23 | 保护模式（暂时拒绝新申请） | A | `ProtectionMode` + `JoinBurstGuard`（自动开/惰性解除）+ `MemberJoinHandler`（**真的移出**，不再只发提示） |
| GM-24 | 风险评分 | **A** | 已接进入群链路：`TelegramBotHandler.toMemberJoined`（采集 `hasUsername`/`languageCode`）→ `MemberJoinHandler`（保护模式**之后**、欢迎语**之前**）→ `JoinRiskScorer.assess`；装配 `BotWiring.memberJoinHandler` 的 `tgg.risk.*` 参数（**未配权重表 = 不建评分器**，即默认不启用）。**同波修掉前提缺件**：`JoinSignals` 原本只能表达"确知的账号年龄"，而 Telegram 不暴露注册时间（`javap` 实测 `User` 无该字段）、头像也需额外 API——若照直填 0/false，**每个新成员都会被判新账号/无头像**；现"未知/未采集"可表达（`OptionalInt` / 可空 `Boolean`），且`JoinRiskScorer` 对未知一律不计入。**本次重测的更正项**：原记 C（"实测零引用 → 未接入任何入群门禁"） |
| GM-25 | 高分拒绝 | **A** | `RiskPolicy`（阈值/权重 + 构造期 fail-fast）+ `RiskAssessment`（三档 ALLOW/REVIEW/REJECT）经上一条同一链路接线；落点：REJECT → 移出 + 如实回执（移出失败不谎报），REVIEW → **只留痕不公开标记**。**本次回写**：原记「缺管理员通知通道」已过时——`AdminReviewNotifier` 通道已建成（实现 `BotReplyAdminReviewNotifier`），由 `tgg.risk.review-notify-chat` 配置驱动，在 `BotWiring:836` 三态装配（未配置/空 = `null` = 不启用，行为同前；配了管理会话 ID = 启用；非法值启动期抛），`MemberJoinHandler:181-183` 的 REVIEW 落点已持有并投递（投递失败只留痕、不影响欢迎语，不假装已通知）。**仍未做**：`hasAvatar` 采集（需每成员一次 `getUserProfilePhotos` 往返）——登记为不接而非隐藏 |
| GM-26 | 第二渠道验证 | **X** | `SecondaryVerificationFlow` 已删除（2026-10-03 清理，决策 §7.1「不接」——运营能力，无自动化装配需求方，git 可找回） |
| GM-27 | 账号异常检测 | C | `AnomalyDetector`（只标记不处罚，输出 `Verdict` 交人工复核）；**实测检测逻辑本身无调用方**——其 `Verdict` 枚举仅被 `DeviceLinkAnalyzer`（同为零引用孤儿）复用 |
| GM-28 | Bot 防拉群机制 | E | 未读到决定它的实现 |
| GM-29 | 年龄确认（COPPA） | D | 未找到实现类（待决 B4） |
| GM-30 | 未成年人保护 | D | 未找到实现类（待决 B4） |
| GM-31 | 权限系统（角色+校验） | A | 能力已接线：`MemberRolePort`/`TelegramMemberRoleAdapter`（实时解析 creator/administrator）+ 命令层管理员校验 + `MemberRole`/`CommandActor`。**证据更正**：原引的 `PermissionChecker` 曾实测零生产引用，**已删除**（2026-10-03 清理，决策 §7「不接」，git 可找回） |
| GM-32 | 三级限流（用户/群组/全局） | **A** | `RateLimiter` + `RateLimitPolicy` + `RateLimitDecision`；**已接进消息判定链**——`BotWiring:729` 建 bean（默认值 `tgg.guard.rate.*`：window `PT10S`、user 5、group 20、global 100，**均非零即默认启用**），`MessageGuardOrchestrator:82-94` 与链接/媒体/重复并列持有。三层**命中即短路**（避免一人打满配额而拖累同群）。**本次重测的更正项**：本行原记 C（"接入深度未核实"） |
| GM-33 | 日志脱敏（Token + 用户 ID） | **A** | `LogSanitizer` 的带盐方法 `maskUserId(long, String salt)`（盐必填 + 48 bit；无盐重载仅向后兼容）**已在生产链上**：`UserIdMasker:78` 调用它，而 `UserIdMasker` 由 `BotWiring:151` 按 `tgg.log.salt` 注册为 bean、注入 `TradeNotifier`（`BotWiring:986`）等。**本次重测的更正项**：本行曾记 B（"生产侧无任何调用方"，实测 `grep 'maskUserId('` main 为空），现已接线 |
| GM-34 | 数据备份（MySQL + Redis） | **A** | **A｜已落地（2026-10-03，运维动作）**：`scripts/backup-db.sh`（mysqldump `--single-transaction` + gzip + 保留策略 + 小文件失败保护；`--set-gtid-purged=OFF` 以便跨实例恢复；环境变量与 datasource 同源 `TGG_DB_*`）。**本机实测**：连跑 4 次保留 3 份 ✓；**恢复演练**（灌入临时库）11 张表完整 ✓。Redis 维度不适用——代码库无 Redis 依赖（原 SPEC「MySQL + Redis」按现状只备 MySQL，脚本注释已说明） |
| GM-35 | 用户首次使用引导 | **X** | `JoinOnboarding` 已删除（2026-10-03 清理，决策 §7「不接」——缺"已引导过"的持久化，git 可找回） |
| GM-36 | 通知三维度（可见范围/推送/留存） | A | `NoticePolicy` + `TradeEvent` + `TradeNotifier`（**本会话接线**；`EPHEMERAL` 维度未映射——当前事件全为 PERMANENT） |

### 2.2 担保交易（ET 01–80）

| 编号 | 功能 | 判定 | 证据（实测） |
|---|---|---|---|
| ET-01 | 交易入口 | **A** | `TradeCommandHandler` + `BotWiring`/`BotDispatcher`（**旧标"Bot 接线待 S1"已过时**）+ `TradeApiController`/`TradeInviteController` |
| ET-02 | 交易状态查询 | A | `TradeStatusView` |
| ET-03 | 信用分联动 | **A** | 链已通：`CreditScore`（五维加权 0–100：完成数 / 争议率 / 好评率 / 最大对手占比 / 活跃天数）→ `TraderCreditService.breakdownOf`/`scoreOf`（口径落地，`:222`/`:241`）→ `CreditCommands.handleCredit:132`（`/escrow credit` 的用户可见出口）。**本次重测的更正项**：原记 C（"实测生产零引用 → 未联动进交易准入"）。**仍未联动进交易准入**——本波接的是"用户可查"这条链，`TradeAdmissionGate` 仍未消费评分。**④ 差距 1 已落地（2026-10-01）**：权重从硬编码改为 `tgg.credit.weights`（`CreditScore` 实例化、构造期校验总和=1） |
| ET-04 | 与群管理联动（欺诈触发封禁） | **C** | **本次部分落地（「建议→投递」环已建成）**：`FraudLinkage.Action` 增中文 label（不动作/建议关注/建议封禁）+ `AdminReviewNotifier.notifyFraudRecommendation`（default 空实现不谎报）+ `fraudMessage` 同源文案工厂（含「仅建议不执行」声明）+ `BotReplyAdminReviewNotifier` 真实投递到管理会话，`FraudNotificationTest` 4 用例钉文案。**信号源已接第一源（2026-09-30 · 拍板③渐进式）**：`FraudLinkageService`（交易达成后评估）——豁免（`NewcomerBadge` 前 3 笔）→ 互刷判定（`MutualBrushDetector` 双向互指超阈值 0.6）→ `FraudLinkage.recommend`（单信号=WATCHLIST）→ `AdminReviewNotifier.notifyFraudTradeRecommendation` 投递管理会话（文案 `fraudTradeMessage` 含双方 ID+「仅建议」）；`FraudLinkageServiceTest` 4 用例 + 文案验收钉住。**仍未接**：设备/异常两源（无采集点，落位后并入 recommend 凑 ≥2 信号）；执行侧 `ModerationOrchestrator` 不变（只建议不执行）。**2026-10-03 改判（B→C）**：群管理整体移除后**执行侧已不存在**——本条的「联动执行」挂点不复存在；情报侧（评估→投递）完整保留。 |
| ET-05 | 交易群创建引导 | C | `TradeGroupLifecycle`（状态机，构造时即绑定唯一交易 ID）+ `EscrowGroupGuide`（三步文案流，末步后 `next` 返空）；**两者均生产零引用** → 未接线 |
| ET-06 | 交易群机器人公告 | C | `TradeMessageMapPort` 的**端口 + JPA 实现（`JpaModerationAdapters.JpaTradeMessageMapPort`）+ 配置装配（`ModerationPersistenceConfig:54`）三件都在**，但**无消费方**——没有任何"发公告"的调用点 |
| ET-07 | Web 交易界面 | **A** | **本次更正（原 E「未读到实现」已过时）**：Mini App 交易页已建成——`tgg-app/src/main/resources/static/miniapp/index.html`（`<title>担保交易</title>` + Telegram WebApp SDK，2026-09-24 落盘）+ 契约测试 `MiniAppPageTest` + 交易 API `TradeApiController`（`POST /create` 等）/`TradeInviteController` + `WebAppInitDataVerifier`（`initData` 验签）+ `SecurityConfig:86` 路由公开。全链（页面/API/验签）在位；真机可用性待部署核销。**2026-10-03 增补**：Vue 工程（`web/`）落地——发起/接受（`TradeCreate.vue`）、订单中心（`TradeStatus.vue`：状态查询 + TON Connect 资金操作）、违禁词管理（`AdminWords.vue`），构建通过（C2 已核销）；新增 `POST /api/trade/status`（域视图 `TradeStatusView` 同源文案 + 当事方限定）。**规划偏差**：README 规划的「三步创建（选角色→填信息→签名）」未做——Web 交易界面的既定流程是邀请流（生成链接/接受），按现状迁移不另造流程 |
| ET-08 | Telegram OAuth（ton_proof） | A | `WebAppInitDataVerifier`（`initData` 验签 + `max-age`） |
| ET-09 | TON USDT 支付 | D | 未找到实现类（S5 未部署） |
| ET-10 | TON 合约托管（Tolk） | C | `contracts/EscrowContract.tolk` 已实现状态机 + 非裁决资金路径（`Fund`/`Deliver`/`Confirm`/`Refund`/`onBouncedMessage`）+ **Jetton 计价托管**（入金认 TEP-74 `transfer_notification`、出账向我方钱包发标准 `transfer`，`types.tolk` 的 `EscrowExtra.asset` 记录计价资产）+ ET-50 校验；`acton test` **实测 54 passed / 0 failed**（2026-10-02 复跑，随 S5 部署补件 SET/EVIDENCE +7；2026-09-30 为 47）。**未部署**。**费率/存储定案（2026-10-02）**：①出账伴随 TON **已定案**——jetton 路径 `grams("0.05")` 为 jetton transfer 及通知链路的 gas 预算（**非费率**；依据与边界见 `EscrowContract.tolk` 出账区注释块）②存储费以「入金超 amount 余部 + 部署余额」为预算自然留存（不建显式池，明示于同注释）③链上部署已真机核销（B10）——**2026-10-02 更新**：部署编排已落地（`EscrowChainDeploymentService` + `POST /admin/chain/deploy` + `AdnlChainSender.sendDeploy`，合约地址计算与 acton `calculateAddress` 逐位对拍），**只差 testnet 资源执行**（资源清单见 HANDOFF）。（Dispute/Resolve 与升级入口均已实现——见 ET-56；本行原写"争议与多签裁决未做"已过时）**本次重测的更正项**：本行原写"jetton 记账/出账未做"，与合约源码**直接冲突**（同一错误在 §1 又犯一次） |
| ET-11 | 资金冻结/释放/退款 | C | `EscrowTradeService` 的 `lock`/`release`/`refund`（连同 `deliver`/`dispute`/`cancel`）**只调用 `EscrowOrder.markXxx(...)` 落状态**，类内无任何 `Chain*` 引用——**纯状态登记，链上未接**（本轮已回读源码核实）。**2026-10-02 TON Connect 接线**：资金路径改由**用户自己的钱包**签名（合约守卫 sender==buyer/seller）——`EscrowChainTxController`（`/api/escrow/chain-tx` 交易构造 + `/api/escrow/wallet` 地址绑定；initData 验签 + 角色矩阵）+ Mini App 资金区（TON Connect UI）+ V11（用户地址/链上金额落库）+ Deliver/Confirm/Refund codec（acton 向量对拍）。**离线全绿；真机为用户参与步骤（见 ONLINE-VERIFICATION B13）** |
| ET-12 | 交易状态机 | A | `EscrowOrder`（8 态 + fail-closed 守卫） |
| ET-13 | 超时自动处理 | C | `TradeTimeoutPolicy`（按「状态 + 进入时刻 + 当前时刻」判定，并**给出应触发的资金动作**：`DELIVERED` 超时自动放款、`LOCKED` 超时自动退款）；已接线（实测 refs=9）。"自动"仍是**惰性**——无调度器，结算在命令/事件触发时发生 |
| ET-14 | 争议发起与冻结 | A | `DisputeFlow` |
| ET-15 | 多签仲裁（2/3 必含联邦） | A | **S5 三波接线后（2026-09-29）全链生产可达**：Java 规则单一定义 `EscrowMultiSigRule`（联邦+买方 或 联邦+卖方；**买方+卖方无效**）、`PartySignatureVerifier` + `FederationPartySignatureVerifier` 已装配（`BotWiring.partySignatureVerifier`，未配公钥=fail-closed 占位拒一切裁决）、`EscrowVerdictGuard` 经 `EscrowVerdictService` 生产消费、入口 `POST /admin/verdict`。**合约侧 `Dispute`/`Resolve` 已实现**（`acton test` 35 passed，方案 A：链下多签+联邦单方上链执行）——**未部署**（`ChainGateway.resolveDispute` 恒「未执行」抽象点，真机出款待合约上链）。**2026-09-29 修复验签时刻缺陷**：`canonicalBytes` 含 `issuedAt`，而入口曾以服务端 `Instant.now()` 重建裁决单——签发方与验签方字节恒不一致、Ed25519 验签必败（生产裁决链路死路；测试签验同刻未暴露）。现 `issuedAt` 随请求携带（**API 变更：`POST /admin/verdict` 请求体新增 `issuedAt`（epoch second），运维脚本须同步**），`EscrowVerdictService` 新增时效窗口守卫（15min 旧限 + 5min 前向容忍，fail-closed） |
| ET-16 | 多签规则校验 | A | **已接线（S5 Wave 2/3）**：`EscrowVerdictGuard.verifiedParties`（fail-closed 验签）→ `EscrowMultiSigRule.satisfies` 由 `EscrowVerdictService.execute` 生产调用（`4b552c6`），经 `POST /admin/verdict` 可达（`512365d`）；8 用例钉守卫链（含伪造签名剔除、买卖双方组合无效） |
| ET-17 | 证据提交（IPFS） | C | `DeliveryProofSubmission` + `EvidenceHasher`（加盐哈希，见 ET-57）；**IPFS 未接**——实测全仓 `pom.xml` 无 IPFS 依赖，源码里 "IPFS" 只出现在注释中 |
| ET-18 | 交易评价（双方互评） | A | `TradeReview` + `TradeReviewService` + `JpaTradeReviewStore`（本会话修过 `@Transactional` 装配缺陷） |
| ET-19 | 链上事件监听 | **A** | `BalanceCrossVerifier` + `ChainSource`（仅接口）；**实测 `ChainSource` 无任何实现类**，故交叉验证器无源可接。`tgg-chain` 现有唯一的真实网络能力是 `AdnlJettonWalletQuery`（走 `JettonWalletQuery` 接缝，**不是** `ChainSource`）。**2026-10-03 落地（读面 + 对账两件套，判定 C→A）**：① 读面——`EscrowStateQuery` + `AdnlEscrowStateQuery`（`get fun escrowState`；单值解析 + **越界防御**（0-5 外即抛，未知状态绝不喂给对账））+ `ChainGateway.escrowStateOf` + ChainWiring **无条件装配**（只读、不依赖私钥）；② 对账——`EscrowChainReconciler`（链上领先 → **按序回填**（每跳过域守卫；危险不一致（资金滞留/方向相反）→ **只报告零动作**；链下流程领先 → 不动作（登记常态）；重复对账幂等）+ `POST /admin/reconcile` 触发面。测试：解析 4 + 判定矩阵 13 + 端点 3。**形态说明**：「监听」落地为**按需对账**（无调度器非目标下的既定形态——cron 由部署者驱动，同 GM-21 先例）。**未真机**（核销入 ONLINE-VERIFICATION B15）。仍有缺：`ChainSource` 余额面（ET-59 交叉验证的另一半） |
| ET-20 | 合约审计（第三方） | D | 未找到实现类（属外部动作） |
| ET-21 | 时间锁 + 紧急暂停 | **A** | **A｜已落地（2026-10-03）**：① **时间锁** = 升级入口的合约内 72h 冷静期（2026-09-30 落地，见 ET-56）；② **紧急暂停** = 本轮落地——`Pause`/`Unpause`（op 0x7d5a1015/1016；仅联邦、**幂等**）+ **白名单式前置守卫** `pauseExemptOp`（资金/状态推进/裁决/jetton 通知/信任根写入被拦；治理三口与存证**不拦**——暂停≠死锁）+ `EscrowExtra.paused` 第四段扩展（`loadExtraCompat` 缺位默认 false；COMPAT-3 位布局 277→278 已钉）。`acton test` **61 passed**（存量 + 7 个 PAUSE + COMPAT-3 扩展）。**未真机**（部署核销入 ONLINE-VERIFICATION **B14**）。**2026-10-03 操作面闭环**：`PauseMessageCodec`（op/位数/引用三要素两侧独立钉——Java `PauseMessageCodecTest` + acton **PAUSE-0**）+ `ChainGateway.pause/unpause`（联邦钱包发送，复用写链栈）+ `POST /admin/pause`{`/unpause`}（Basic 鉴权；写链栈未接线 **503** fail-closed；**有意不加多签**——止血动作且可逆，理由见 controller 注释） |
| ET-22 | KYC 最小化 + 加密 | D | 未找到实现类（待决 B4） |
| ET-23 | 单笔限制 | A | `TradeAdmissionGate` + `TradeAdmissionPolicy` |
| ET-24 | 单笔冷却期（24h） | A | 同上 |
| ET-25 | 争议期间冻结 | A | 同上（优先级高于冷却期） |
| ET-26 | 维护时间选择（5 选项） | A | `MaintenanceWindow` |
| ET-27 | 超时自动确认 | A | `TradeTimeoutPolicy`（同 ET-13） |
| ET-28 | 维护期争议暂停 | A | `DisputeFlow.pausedRemaining` |
| ET-29 | 平台费自动划转 | **B** | **B1 已拍板（2026-09-29：费率配置化默认 0）→ 口径已落地**：`FeePolicy`（`FeePolicyTest` 5 用例：默认 0 不收费、平台费=金额×费率、平台费与仲裁费分离、fail-fast）+ `sellerNetOnRelease` 放款实收口径。**披露已接（a50b02c）**：release 回执含「平台费 X、卖方实收 Y」（费率 0 静默）、`/admin/verdict` 回执含 `arbitrationFee`/`federationFee` 字段，用户级验收三条全 met。**待接**：真实划转（属 S5 链上出账）。**2026-10-03 增补**：核算结果已**落库为对账台账**（V12 `fee_ledger`：`FeeDisclosure.recordIfReleased` 放款即记、幂等、旁路不阻断；`/admin/fee-ledger` 查询端点）——「划转」的**记账面已接通**；链上出账仍待（合约不做费用扣减为既定架构，见合约注释） |
| ET-30 | 联邦管理员费用划转 | **B** | 同上口径落地：`FeePolicy.federationFee`（联邦管理费从**仲裁费**分成，不从平台费——SPEC 6.5 三费分离）。**披露已接（a50b02c）**：裁决回执 `federationFee` 字段。**待接**：真实划转。**2026-10-03 增补**：费用核算的落库台账（V12 `fee_ledger`）当前覆盖**放款平台费**；联邦费/仲裁费随裁决流程结算（如需独立对账面随裁决迭代） |
| ET-31 | TON Pay 集成 | **C** | **C｜部分实现（2026-10-03）**：服务端面落地——`TonPayReferenceController`（`POST /api/tonpay/reference`：initData 验签 + 仅买方可登记 + 幂等，V13 `tonpay_references`）+ `TonPayWebhookController` 六步（见 ET-32）+ `JpaTonPayReferenceStore`。**缺关键件**：前端 `createTonPayTransfer` 接入（真正产生 reference 的一端）与 Merchant API Key（真机）——两者都在外部（03 文档 §1.2 / UNDECIDABLE §G） |
| ET-32 | TON Pay Webhook 验证 | **A** | **A｜已落地·已接线（2026-10-03）**：`TonPayWebhookVerifier`（HMAC-SHA256 + 恒定时间比较）+ `TonPayWebhookController` 六步（验签 → 仅 `transfer.completed` → reference 匹配 → 防重 → 金额/币种核对 → 结算定格）；fail-closed 三态（未配置 503 / 验签失败 401 / 业务分支 200 且不该结算的一律零调用）。测试：验签 4 + 六步 7 + 登记 4 + 持久化 2。**两处口径待真机核销**（签名原文拼接、payload 字段名——ONLINE-VERIFICATION C4 已更新指向） |
| ET-33 | 交易金额分层验证 | **A** | `AmountTierPolicy` + `RiskPrompt`（随建单响应回传；**旧标 ⬜ 属漏标**） |
| ET-34 | 交易前风险提示 | **A** | `RiskPrompt` + `PendingTradeRegistry`（两步流：create 登记预览 → confirm 校验同买方/参数/未过期后一次性消费；commit `ef01ccf`；**旧标 ⬜ 属漏标**） |
| ET-35 | 首单保障 | **B** | **B2 已拍板（免首单手续费最简形态）→ 口径已落地（本轮）**：`FeePolicy` 加首单豁免（`firstOrderFree` 默认开；`platformFee`/`sellerNetOnRelease` 计数重载——本次前成功笔数为 0 即首单）；`TradeCommandHandler.advance` 在**迁移前**快照卖方成功笔数（迁移后统计已含本次，会把首单误判为非首单）、`feeNote` 据此披露「首单免平台费」；配置 `tgg.fee.first-order-free`（`TGG_FEE_FIRST_ORDER_FREE`，默认 `true`）。**待接**：真实划转（属 S5 链上出账）。注：`NewcomerBadge`（前 3 笔豁免互刷检测）是 ET-38/ET-67，与本条不是一回事 |
| ET-36 | 主动推送 | **A** | `TradeNotifier` + `TradeEvent` + `NotificationOutcome` + `NoticePolicy`（**本会话新增**；对手方通知，fail-soft）。**④ 差距 4（ET-77 周榜惰性推送）已落地（2026-10-01）**：`WeeklyBoardPush`（无调度器，群内交互触发、每周每群最多一条、私聊不推、空榜不推；去重状态在进程内——重启后同周可能多推一次，属已知边界） |
| ET-37 | 交易信用可视化 | **A** | 可视化出口已接：`/escrow credit`（个人面：评分 + 等级徽标 + 五维拆解，`CreditSummaryView.render:62`）与 `/escrow rank`（榜单面：`Leaderboard` + `RankPrivacy`），链路 `CreditCommands.handleRank:73`/`CreditCommands.handleCredit:132`（命令层自 2026-10-03 拆分后由 `TradeCommandHandler` 委托至 `CreditCommands`）。**本次重测的更正项**：原记 C（"两者都没有可视化出口"）；**行号亦已校正**（2026-10-03 抽查：原引 `:453`/`:491` 系漂移，实为 `:567`/`:634`；`handleRank`/`handleCredit` 随后随命令拆分迁入 `CreditCommands`） |
| ET-38 | 交易信用冷启动（新手标识） | **A** | `NewcomerBadge`（**前 3 笔豁免互刷检测**，边界 `completedCount < 3` 定死）**已接线**——原记「仍零生产引用」**已过时**（与同文档 ET-04 行自相矛盾）：评估链 `FraudLinkageService:87-88` 消费它，评分链 `TraderCreditService:161` 也消费它（④ 差距 3）。"新手标识"的用户可见面：`TraderTier.NEWBIE`（`<5` 笔一律新手）经 `/escrow credit` 显示 🌱新手 |
| ET-39 | 交易群生命周期（7 天归档） | C | `TradeGroupLifecycle`（待建 → 已关联 → 留痕中 → 静默期 → 已归档；静默期内发起争议会恢复留痕）**无生产调用点**（实测生产引用 0）——阻塞是**缺"交易群"这条流程**，不是调度器 |
| ET-40 | 交易群静默期 | C | 分两层：**时段判定** `SilenceWindow` 已接线（服务于通知静默，见 GM-18/GM-36）；**交易群自身的静默期阶段**在 `TradeGroupLifecycle`（零引用，见 ET-39） |
| ET-41 | 群成员投票 | **X** | `MemberVote` 簇（含 `VoterRegistry`/`VoterRecusal`/`VoteStake`/`FallbackSettle`/`StakeThresholdAdjuster`/`VoteRewardAllocator`）已删除（2026-10-03 清理，方案一：git 历史即复活路径；决策 §7.1——与定案 A0-2/C1 冲突，若将来推翻方案 A 可经 git 找回） |
| ET-42 | 投票人回避机制 | **X** | `VoterRecusal` 随投票簇一并删除（2026-10-03 清理，见 ET-41 行） |
| ET-43 | 争议证据双通道保存 | **A** | **已全接线（2026-10-02 · S5 写链期）**：`/escrow evidence <订单号> <内容>`（`TradeCommandHandler.handleEvidence`）→ `EscrowDisputeService.recordEvidence` → `EvidenceChannel.record`。群内腿 = 投递到订单绑定的交易群；**链上腿 = `ChainEvidenceSink`（真实现）**——订单链上地址 → `EvidenceHasher.saltedHash`（盐= `tgg.log.salt`，缺失即拒）→ `RecordEvidence` 上链。两腿各自成败进回执（不静默）。**并修复一处缺陷**：先前的"不抛空实现"会被 `trySave` 当作成功、回执曾把"未上链"误报成「已上链」——现"未接线/未部署/发送失败"一律如实回落「未成功」（回归钉：`TradeCommandHandlerTest.evidence*` 三用例）。**真机未验**（合约未部署） |
| ET-44 | 投票奖励分配 | **X** | `VoteRewardAllocator` 随投票簇一并删除（2026-10-03 清理，见 ET-41 行） |
| ET-45 | 证据时效性（24h） | **A** | **已接线（2026-10-02 · 争议链 Wave 2）**：原记「零代码引用」**已过时**——窗口由 `EscrowDisputeService.evidenceDeadline` 提供（配置 `tgg.dispute.evidence-window`，默认 `PT24H`），`recordEvidence` 在记录**之前**先过 `EvidenceDeadline.isExpired`（过期即拒，不留证）。窗口起点 = 会话 `opened_at`（争议进入时刻），**不随维护期暂停** |
| ET-46 | 仲裁员加入费动态门槛 | **X** | `StakeThresholdAdjuster` 随投票簇一并删除（2026-10-03 清理，见 ET-41 行） |
| ET-47 | 投票截止后 FallbackSettle 兜底 | **X** | `FallbackSettle` 随投票簇一并删除（2026-10-03 清理，见 ET-41 行） |
| ET-48 | 重放保护（seqno/nonce） | **X** | `ReplayGuard` 已删除（2026-10-03 清理，决策 §7「不接」——缺 nonce 源：唯一签名面 `initData` 在同一会话内被重复携带，按 nonce 拒会打断正常使用，git 可找回） |
| ET-49 | 交易阶段标注 | **A** | **2026-10-03 复核改判（B→A）**：`TradeStage` 已接通——`TradeStatusView.of` 调用 `TradeStage.of(state)`（tgg-escrow main），经 Bot `/escrow status` 与 Web `POST /api/trade/status` 出口渲染；原「main 零引用」记录已过时。 |
| ET-50 | Fake Jetton 验证 | C | **合约侧已实现**：`contracts/EscrowContract.tolk:239` 断言 `in.senderAddress == loadExtraCompat(storage.extra).ownJettonWallet`，失败抛 `Errors.NotTrustedWallet`（定义见 `contracts/types.tolk:16`），行内注释即标 ET-50；**未部署**。**本次重测的更正项**：本行曾记 D（"未找到实现类"），与同文档 ET-10 行"已实现 ET-50 校验"**自相矛盾**，实测以本行为误 |
| ET-51 | 链上存证 | **A** | **已接线（2026-10-02 · S5 写链期）**：原记「未接任何链上路径」**已过时**——合约侧新增 `RecordEvidence` 消息（`contracts/types.tolk` 0x7d5a1014，哈希进 `EscrowExtra.evidence` map，发送者限买方/卖方/联邦）；Java 侧 `RecordEvidenceMessageCodec`（acton 向量位级对拍）+ `ChainGateway.recordEvidence` + `ChainEvidenceSink`（`/escrow evidence` 链上腿）。链上是**加盐哈希**（原文不出本机）。**真机未验**（合约未部署） |
| ET-52 | x402 Proof Hash | C | 概念落在 `deliveryProofHash`（不绑 x402，见 SPEC 6.5）；实现是 `DeliveryProofSubmission`（纯函数，仅买方、仅 `DELIVERED` 状态可提交） |
| ET-53 | 交付证明上链 | C | **合约侧已具备接收能力（2026-10-02）**：`RecordEvidence` 路径即哈希上链通道（ET-51），交付证明可复用；但 `DeliveryProofSubmission` 类本身**仍未接任何链上路径**（其 javadoc「合约侧尚未接收或计算该哈希」的结论对 2026-10-02 之前成立）。接线是下一步（结构与存证腿同构） |
| ET-54 | 防钓鱼提示 | C | `PhishingNotice` |
| ET-55 | Permit Signature 钓鱼防御 | C | SPEC 6.3 定为**不制造授权面**（设计层面的规避，非独立实现） |
| ET-56 | 代理合约可升级性 | C | **⑥ 已落地（2026-09-30，方案 a + c 兜底）**：合约内置升级入口——`ProposeUpgrade`/`ApplyUpgrade`/`CancelUpgrade`（仅联邦，与 Resolve 同信任根）+ **合约内 72h 时间锁**（`UPGRADE_COOL_DOWN_SECS`，Apply 判 `now ≥ proposedAt + 72h`）+ `setCodePostponed` 换码 + 事件留痕（`UpgradeProposed/Applied/Cancelled` 外部日志）+ `EscrowExtra.upgrade` 提案位与 `loadExtraCompat` 容错读取（**旧数据→新代码兼容硬门 COMPAT-1 已钉住**，变异回退严格解析即 Cell underflow 红）。`acton test` **47 passed**（35 存量 + 12 新增，含 PROP/APPLY/CANCEL/STATE/COMPAT 矩阵）。**Java 写链栈（B 案）**：`UpgradeMessageCodec`（跨语言位级向量对拍 Tolk toCell hash）+ `ChainMessageSender`/`AdnlChainSender`（钱包签名+ADNL 广播，三接缝离线替身）+ `EscrowUpgradeService`（链下多签守卫复用 EscrowMultiSigRule 范式）+ `ChainGateway` 升级三方法，`mvn` tgg-chain/tgg-escrow 新增 26 用例全绿。**缺口（2026-09-30 装配闭环后）**：未部署、未真机验证（签名载荷口径「签 transferBody hash」为推定项，见 ONLINE-VERIFICATION B7-B9）。升级操作面已闭环：`BotWiring` 装配写链栈（`tgg.chain.upgrade.wallet-seed-base64`，空=未接线 fail-closed）+ `POST /admin/upgrade/propose|apply|cancel`（EscrowUpgradeService 多签守卫在前、chainExecuted 如实回报）+ `GET /admin/upgrade/status`。链上降级（回滚 code）**不可行**（旧解析器读新 data 抛 9，实测）——兜底只能走方案 c（新部署+逐单迁移），操作手册见 UNDECIDABLE-DECISIONS ⑥ |
| ET-57 | 预映像攻击防护（哈希前加盐） | C | **已回读源码（2026-09-29 复核）**：`LogSanitizer` 侧已加盐（见 GM-33）；`EvidenceHasher.saltedHash(String, String)` 亦已加盐且**盐必填**（空白即抛）。**合约侧尚未兑现**（同 ET-51） |
| ET-58 | TON 异步消息顺序处理 | C | 两侧守卫**均已实现且有用例**：Java 侧是 `EscrowOrder` 的前置条件；合约侧「每个分支先验前置状态」，`acton test` 的 `refund rejected after release` / `unknown message is rejected` / `deliver does not pay anyone` 即覆盖越级。**缺的是部署与真机乱序验证**。**本次重测的更正项**：本行原写「合约侧待做」，与合约头注释（把 ET-58 列为已遵循的纪律）冲突 |
| ET-59 | 资金流向链上验证 | C | `BalanceCrossVerifier` **要求 ≥2 个独立源交叉验证**（可用源 <2 即抛 `ChainUnavailableException`）；但**实测 `ChainSource` 无任何实现类** → 该验证器当前无源可接 |
| ET-60 | 争议双方陈述 | **A** | **已接线（2026-10-02 · 争议链 Wave 1）**：原记「实测零引用」**已过时**——`/escrow statement <订单号> <陈述>` 与 `/escrow read <订单号>`（`TradeCommandHandler.handleStatement/handleRead`）→ `EscrowDisputeService` → `DisputeStatementFlow`（规则只在后者定义），会话落新表 `escrow_disputes`（V7）。可选裁决前置门 `tgg.dispute.require-statements`（默认 **false**，不改变既有 `/admin/verdict` 路径） |
| ET-61 | 多维度惩罚 | **A** | **已接线（2026-10-02 · 争议链 Wave 3）**：原记「零代码引用」**已过时**——`PenaltyService`（败诉台账 `dispute_losses` V8 + `WarningCountQuery` 跨群警告计数）→ `PenaltyPlanner`。败诉口径 = **outcome 不利方**（用户 2026-10-02 拍板；单点定义 `PenaltyService.loserOf`）。执行侧：`TRADE_LIMIT`/`COOLDOWN_EXTEND` 经 `TradeAdmissionGate.check(context,now,penalties)` 作用于准入（降 1 / 翻倍）；`PUBLIC_NOTICE` 由裁决回执 `loserPenalties` 披露。`VOTE_SUSPEND` 在本部署**无对象**（无投票层）——计划仍会列出，但无消费者。**⚠ 2026-10-03 群管理移除后**：警告**写面**已删（`warnings` 恒空）——「跨群警告」维度读数**恒 0**（读面与装配链完整保留；`PenaltyPlanner` 该入参当前恒 0，L3 审查已核） |
| ET-62 | 社区氛围建设（周榜/月报） | **A** | 榜单已接线：`/escrow rank [条数]`（`CreditCommands.handleRank:73`）→ `TraderCreditService.rank:292`（**先排后截**）→ `Leaderboard.render`；聚合来自 `JpaTraderStatsPort`（`escrow_orders` / `trade_reviews`，无新表）。**差异**：为**命令触发**的累计榜，**无周期口径**（周/月/季不切分）——项目"不引入调度器"非目标 |
| ET-64 | 设备关联检测 | C | `DeviceLinkAnalyzer`（复用 `AnomalyDetector.Verdict`；只标记不处罚）**零代码引用**（仅两处 javadoc 提及） |
| ET-65 | 互刷模式检测 | **A** | `MutualBrushDetector`（双向互刷判定，与单向的 `CounterpartyDiversityScorer` 互补）**已接线**——原记「零代码引用」**已过时**：评估链 `FraudLinkageService:91` 消费它（信号源第一源），评分链 `TraderCreditService:171` 也消费它（④ 差距 3 互刷不累加） |
| ET-66 | 新账号交易限制 | C | `NewAccountLimitPolicy` **全仓零引用**——策略在、无调用方 |
| ET-67 | 互刷不累加信用 | **A** | **已落地（2026-10-01，④ 差距 3）**：原记「无落点」**已过时**——落点就在信用累加侧 `TraderCreditService.adjustForBrush:156`（评分/榜单/摘要共用），判据与阈值同评估链（`tgg.fraud.concentration-threshold`），新手豁免同源 `NewcomerBadge`，剔除笔数在 `/escrow credit` 文案披露 |
| ET-68 | 可疑交易关系标记 | **X** | `SuspiciousRelationPolicy` 随投票簇一并删除（2026-10-03 清理，见 ET-41 行） |
| ET-69 | Comment 钓鱼提示 | **A** | `PhishingNotice`（与 GM-13 / ET-54 / ET-71 合并定义）**已接线**（2026-10-01）——原记「零引用」**已过时**：随周榜惰性推送按周轮换送达（`WeeklyBoardPush.compose`） |
| ET-70 | 官方不通过 comment 发奖励 | E | 属行为约定，未读到决定它的实现 |
| ET-71 | Permit Signature 警示 | **A** | 文案在 `PhishingNotice` 的第三类（授权钓鱼），**已接线**（2026-10-01）——与 GM-13/ET-54/ET-69 同一路径（周榜惰性推送按周轮换）；原记「零引用」已过时 |
| ET-72 | 交易对手多样性评分 | **A** | 占比口径的唯一出处 `CounterpartyDiversityScorer.maxShare` 已被 `TraderCreditService.diversityShareOf:205` 消费（评分的一个维度，并在 `/escrow credit` 的"对手集中度"一行里可见）。**另一半已于 2026-10-01 接线**：`MutualBrushDetector` 现由评分链消费（`TraderCreditService.adjustForBrush:156`），故「评分侧已接、反作弊侧未接」的现状**已消除**；两处仍共用同一 `maxShare`，口径未分叉 |
| ET-73 | 账号异常检测 | C | `AnomalyDetector`（与 GM-27 同一实现；只标记不处罚）**检测逻辑无调用方**，仅其 `Verdict` 枚举被同样零引用的 `DeviceLinkAnalyzer` 复用 |
| ET-75 | 交易信用综合评分 | **A** | `CreditScore.compute` 经 `TraderCreditService.scoreOf:241` 接线，评分在 `/escrow rank` 与 `/escrow credit` 两条回执里都出现。**B3 的"等级条件冲突等 5 点"已按「笔数门槛优先」定案**（`TraderTier`），量表满分线（50 笔 / 365 天）已提为 `CreditScore.COMPLETED_FULL`/`ACTIVE_DAYS_FULL` 公开常量，供渲染复用而不另写一份 |
| ET-76 | 交易者等级评定 | **A** | `TraderTier.tierOf` 经 `TraderCreditService.tierOf:248` 接线；等级徽标出现在 `/escrow credit`（另带中文等级名，🌱 对用户不自明）与 `/escrow rank` 两条回执里 |
| ET-77 | 周榜推送 | **A** | 榜单已接线，但触发方式是**命令**而非定时：`/escrow rank [条数]`（`CreditCommands:73`，默认 10、上限 20——上限是为了不让一次命令刷屏）。**未做**：周期切分与主动推送——"不引入调度器"是项目非目标，故口径为"谁看谁是此刻的累计榜" |
| ET-78 | 月榜推送 | **A** | 同上（同一 `Leaderboard`；**无月度独立口径**——命令触发，不按自然月切分） |
| ET-79 | 季度荣誉榜 | **A** | 同上（无季度独立口径；`Leaderboard` 的奖牌前缀 + 等级徽标即荣誉榜的展示面） |
| ET-80 | 排名隐私控制 | **A** | **已接线（2026-10-02 · ET-80 opt-in 落地）**：原记「部分实现：默认脱敏已生效、主动公开未做」**已过时**——`user_prefs` 加列 `rank_public`/`public_username`（**V9** 迁移）+ `UserPreferencePort.rankVisibilityOf/setRankPublic`（JPA 实现 `JpaModerationAdapters.JpaUserPreferencePort`；**未登记 / 从未表态恒为不公开**，默认脱敏语义不变）+ `/escrow publish|unpublish`（`TradeCommandHandler.handlePublish`；无 @username 时 publish 被拒并提示先去设置，不落成无效开关）+ `@username` 经 `CommandActor.username` 传入（`TelegramBotHandler` 消息与回调查询两个构造点）。消费面与 `/escrow rank` **同口径**（`Leaderboard.render` 逐人按其设置渲染）：默认脱敏显示脱敏 ID、opt-in 公开者显示 `@username`——`handleRank` 与 `WeeklyBoardPush.compose` 均逐人查 `rankVisibilityOf` 实现 |

### 2.3 计数（2026-10-03 · 链上对账批次重算——ET-19 C→A；含同日运维/TON Pay/合约暂停批次；由 §5 命令复算）

| 判定 | 群管理 | 担保交易 | 合计 |
|---|---|---|---|
| **A｜已落地·已接线** | 24 | 42 | **66** |
| **B｜已落地·未接线** | 0 | 3 | **3** |
| **C｜部分实现** | 3 | 22 | **25** |
| **D｜未找到实现类** | 2 | 3 | **5** |
| **E｜未核实** | 2 | 1 | **3** |
| **X｜已删除（清理，git 可找回）** | 5 | 7 | **12** |
| 合计 | 36 | 78 | **114** |

> 本表**由命令从上方逐项表算出，不是手写**，命令见 §5 第 4 条——注意它**必须限定在 §2 区域内**：
> 早先记录的那条 `awk -F'|' '/^\| (GM|ET)-[0-9]+/{print $4}'` 会连 §6 审计表的 4 行一并命中
> （实测 118 行、合计被算成 118，并混入 4 个非判定值）。这是本轮发现的**文档自身缺陷**，已修正并记入 §6。
>
> 与上一版（`HEAD=14bbf6d` 时点：27/9/59/15/4）的差异来自上一轮重测**共修正的 4 行**：GM-08、GM-33 的 B→A 与 ET-50 的 D→C（§6.1），以及 C 级逐行回读中 GM-32 的 C→A（§6.2 批 2）。
>
> **本轮（信用/榜单读模型接线，`docs/archive/plans/交易者信用-榜单读模型接线-…md` 的 Wave 1–3）的差异**：
> ET 侧 **9 行 C→A**——ET-03 / 37 / 62 / 72 / 75 / 76 / 77 / 78 / 79。这 9 行原是"有类无用户出口"，
> 现由 `/escrow rank`（榜单）与 `/escrow credit`（个人摘要）两条命令接上真实数据，
> 故 A 从 30 升到 39。**当时未变**：ET-38（`NewcomerBadge` 豁免链仍零引用）、
> ET-80（默认脱敏已生效、主动公开未做，仍记部分实现——该行已于 2026-10-02 落地，见下段）。
>
> **本轮（ET-80 opt-in 落地，2026-10-02 · 表中数值已按 §5 第 4 条命令重算至此日时点）的差异**：
> ET 侧 **1 行 C→A**——**ET-80**「用户主动公开用户名」从计划 Wave 4 的延期项变为已接线：
> `user_prefs` 加列 `rank_public`/`public_username`（V9）+ `/escrow publish|unpublish` +
> `handleRank`/`WeeklyBoardPush` 双消费面同口径（默认脱敏、opt-in 公开者显示 `@username`）。
> 因该行同时是所有历史轮次增量的并集，本表数字与早前各"轮差异"段落的中间值不必逐一对齐——
> 复算命令见 §5 第 4 条，任何人可重跑对账。
>
> **S5 轮（争议裁决接线，2026-09-29）的差异**：ET 侧 2 行升级——**ET-15 C→A**（多签仲裁全链生产可达：
> 验签器装配 + `EscrowVerdictGuard` 生产消费 + 合约 `Dispute`/`Resolve` 已实现，未部署不影响接线判定）、
> **ET-16 B→A**（`EscrowVerdictGuard` 由 `EscrowVerdictService` 生产调用，`/admin/verdict` 可达）。
> A 42→44、B 6→5、C 48→47。
>
> **清理轮（2026-10-03，方案一）的差异**：16 个「明确不接/与裁决定案冲突」的类删除 → 对应 12 行
> 判定改为 **X（已删除）**：GM 侧 5 行（GM-05/15/19/26/35——GM-02 的 `MuteSchedule` 亦删但行判定保持 A，
> 其 ①② 两层能力仍在）、ET 侧 7 行（ET-41/42/44/46/47/48/68）。
> B 9→5（GM 侧 3 个 B 类全删归零）、C 32→24、X 0→12；A/D/E 不变。

### 2.4 「缺调度器」诊断更正（2026-09-25 实测）

本版初稿沿用了"缺调度器"当阻塞理由，**实测后判定这个诊断基本是错的**。逐类查生产调用点
（`grep -rl <类> --include=*.java tgg-*/src/main`，排除自身与 javadoc 提及）：

| 类 | main 引用 | 实情 |
|---|---|---|
| `KeywordAutoReply` | 2（`BotDispatcher:32/61` 真实字段注入） | **已接线** ✓ |
| `TradeGroupLifecycle`（**未删**，见 ET-05/39） / ~~`MuteSchedule`~~（**已删除**，2026-10-03，见 §7.1.1） / `PhishingNotice`（**已接线**，见 GM-13） | —（本行原「0 引用」记录已各自过时/处置） | 已处置（删除或接线） |
| `Leaderboard` | ~~0~~ → **2**（`CreditCommands:100`、`WeeklyBoardPush:166`） | **已接线**——本表原把它列为无调用点；`/escrow rank` 落地后更正（2026-09-29）；**2026-10-03 锚点校正**：原引 `TraderCreditService`/`TradeCommandHandler` 的旧行号（`:131`/`:339`）系命令拆分前值（`handleRank` 已迁入 `CreditCommands`，渲染调用点见新锚） |
| `EvidenceDeadline` | 1（仅 `DisputeStatementFlow:44` 的 `{@link}`） | 无真实调用 |
| ~~`JoinVerificationFlow`~~ | —（**已删除**，2026-10-03，见 §7.1） | 已处置（删除） |

**结论**：这些类的阻塞**不是缺调度器，而是没被接进任何流程**——所以"惰性化"这条替代路也走不通
（没有触发点可挂）。真正的解锁条件是**上游流程成立**：交易群流程（ET-05/06/39/40）、
证据流（ET-17/43/45）、榜单聚合（ET-75/76/77）。

> **后记（2026-09-29）**：上句里的"**榜单聚合（ET-75/76/77）**"**已成立**——信用/榜单读模型接线
> 把 `/escrow rank` 与 `/escrow credit` 接上真实数据（`Leaderboard` 的生产引用由 0 → 2）。
> 另两条（交易群流程、证据流）**仍未成立**，故本节的诊断对它们依然有效。**唯一属"真需定时"的是**：
禁言解除（GM-02）、60s 入群验证超时（GM-15）、周期推送（GM-13/ET-77~79）——这些按项目
"不引入调度器"的非目标**降级或另议**，不应当作可开工项排进波次。


## 2.5 机械实测：文本零引用且无注解装配的类（本轮新增，可复算）

> 这一节取代"靠手感判断哪些没接线"。它由一条命令产出，任何人可重跑、可对账。

**口径**：在 `tgg-*/src/main` 下对每个类名统计文本引用数，**排除自身文件、排除 javadoc 与行注释**；
引用数为 0 **且**类上没有 Spring 装配注解（`@Component/@Service/@Repository/@RestController/
@Controller/@Configuration/@Entity/@Bean/@SpringBootApplication`）者，列为
「**零引用且无注解装配**」。

**为什么必须排除注解类**：`TradeInviteController`、`AdminChainController`、`ModerationPersistenceConfig`
这类靠注解被 Spring 装配，文本引用天然为 0——不查注解就会把它们误报成"未接线"。
**本轮第一次统计正是踩了这个坑**（27 个候选里有 4 个属此类误报），说明 §6 记录的方法学盲点至今仍会复现。

**本轮实测（三波接线后；`HEAD` 见 §1）共 29 个**：

`AdminRegistry` `BalanceCrossVerifier` `ChainVerificationNotice`
`DeliveryProofSubmission` `DeviceLinkAnalyzer`
`DisputeStatementFlow` `EscrowGroupGuide` `EscrowVerdictGuard` `EvidenceChannel`
`EvidenceDeadline` `FeatureToggle` `FederationPartySignatureVerifier` `FraudLinkage`
`JoinOnboarding` `JoinVerificationFlow` `MuteSchedule`
`MutualBrushDetector` `NewAccountLimitPolicy` `NewcomerBadge` `PenaltyPlanner`
`PermissionChecker` `PhishingNotice` `ReplayGuard` `SecondaryVerificationFlow`
`StakeThresholdAdjuster` `SuspiciousRelationPolicy` `TradeGroupLifecycle` `TradeStage`
`VoteRewardAllocator`

**与上一版的差异（印证本文档确实滞后）**：
- `JoinRiskScorer` **已不在列**：入群风险评分接线后，它由 `MemberJoinHandler` 持有并逐人评估
  （GM-24/25）；同时修掉了它的前提缺件——不可得的信号（账号年龄/头像）曾被当成可得的填默认值。
- `ChannelPuppetGuard` **已不在列**：频道傀儡防御接线后，它由 `MessageGuardOrchestrator` 持有并参与判定
  （GM-12，判定序位在链接之前）；同时修掉了它的前提缺口——频道帖此前在入站构造期抛异常。
- `CounterpartyDiversityScorer` / `CreditScore` / `Leaderboard` **已不在列**：信用/榜单接线后，
  `CreditScore` 经 `TraderCreditService` 接上评分链、`CounterpartyDiversityScorer` 被其消费、
  `Leaderboard` 被 `CreditCommands.handleRank:73` 引用（Wave 1–3）。这三者此前在列，
  不是"能力不存在"，而是**没有出口**——再次说明本节只是**下界**。
- `LogSanitizer` 与 `MessageRepeatDetector` **已不在列**：前者经 `UserIdMasker` 接进了日志路径，
  后者已进 `MessageGuardOrchestrator` 的判定链（GM-08）。上一版把二者记为"未接线"，已过时。
- 上一版的 9 项"未接线"清单只覆盖了其中 7 个；另有 27 个类当时被归在 C 级（"部分实现"）。
  但从"是否被任何生产代码引用"这一维度看，它们同样是零引用。**两个口径回答的是不同问题**：
  C 级说的是"有链路但缺件"，本节说的是"文本上没有任何生产代码引用它"——两者可以同时为真。

**2026-10-03 清理后重算（§5 命令 3 实测）**：上列 29 个中，13 个「明确不接/与裁决定案冲突」的类已删除
（`AdminRegistry`/`FeatureToggle`/`JoinOnboarding`/`ReplayGuard`/`JoinVerificationFlow`/`SecondaryVerificationFlow`/
`MuteSchedule`/`PermissionChecker` + 投票簇 `MemberVote`/`VoterRegistry`/`VoterRecusal`/`VoteStake`/`FallbackSettle`/
`StakeThresholdAdjuster`/`VoteRewardAllocator`/`SuspiciousRelationPolicy`，共 16 个，各见 §2 对应行），
另有 11 个此后已接线（争议链/TON Connect 波）。**当前零引用孤儿仅剩 5 个——全部是「将来要接」的件**：

`BalanceCrossVerifier`（ET-19/59：待 ChainSource 实现） `ChainVerificationNotice`
`DeliveryProofSubmission`（ET-52/53：接线是下一步） `DeviceLinkAnalyzer`（待设备信号源）
`NewAccountLimitPolicy`（ET-66：账号年龄不可得）

> ⚠️ **本节局限**：文本零引用 **≠** 功能不可达（注解装配、反射、SPI 都会让引用数失真），
> 也 **≠** 能力没被实现。它是一条**可机械复算的下界**，不是最终判定；最终判定仍须看 §2 并逐行复算。

## 3. 支撑类（不占功能编号，实测存在）

- **持久化**：`EscrowOrderRepository` / `JpaEscrowOrderStore` / `JpaTradeHistoryPort` / `JpaTradeInviteStore` / `JpaTradeReviewStore` / `JpaModerationAdapters` / `TradeInviteRepository`
- **端口与适配器**：`GroupAdminPort`(+`TelegramGroupAdminAdapter`)、`MemberRolePort`(+`TelegramMemberRoleAdapter`)、`ChannelMembershipPort`(+`TelegramChannelMembershipAdapter`)、`BotReplyPort`(+`TelegramBotReplyAdapter`)、`TradeMessageMapPort`、`UserPreferencePort`、`WarningPort`
- **命令与会话**：`CommandParser` / `BotCommand` / `TradeEvent` / `NotificationOutcome`
- **联邦与链上**：`FederationKeyPair` / `BalanceCrossVerifier` / `ChainSource` / `VerifiedBalance`
- **交易服务与准入**：`EscrowTradeService` / `TradeInviteService` / `TradeMaintenanceService` / `TradeReviewService` / `TradeAdmissionGate` / `PendingTradeRegistry` / `RiskPrompt`
- **合约工程（S5）**：`Acton.toml` + `.acton/` + `wrappers/EscrowContract.gen.tolk` + `tests/EscrowContract.test.tolk`（Acton v1.2.0，`acton build`/`acton test` 通过）

## 4. 结论

**一句**：逐项实测 114 行（GM 36 + ET 78；其中 GM-36 取 SPEC 6.6 节的定义，SPEC 状态表本身缺该行），
**已落地 69 条（66 已接线 + 3 未接线）**、**部分实现 25 条**、未找到实现 5 条、未核实 3 条、
**已删除 12 条**（2026-10-03 清理，git 可找回）。
（数字为 2026-10-03 · 链上对账批次后由 §5 命令 4 重算。）

> **本版累计变化（三波接线 + S5 写链期）**：① **信用/榜单读模型**（Wave 1–3）——ET 侧 9 行 C→A
> （ET-03 / 37 / 62 / 72 / 75 / 76 / 77 / 78 / 79）；② **频道傀儡防御**（GM-12）——GM 侧 1 行 B→A；
> ③ **入群风险评分**（GM-24 / GM-25）——GM 侧 2 行 C→A；④ **S5 写链期（2026-10-02）**——
> ET-80（排名隐私 opt-in）与 **ET-51（链上存证）** 各 1 行 C→A；ET-43 链上腿由空实现转真实现、
> ET-10/11 补部署编排注记。**A 现为 66**（2026-10-03 L3 审查批次 ET-49 B→A 复核改判后；ET-04 同批改判 B→C）——此前链上对账批次后为 65。
> ①②③ 的共同形态值得记一笔：**它们都不是"新写能力"，而是补上"最后一跳"**——
> ① 缺的是用户可触达的出口，②③ 缺的是**前提**（②：消息模型承载不了该消息；③：信号不可得却被当成可得）。
> **前提缺件往往比接线本身更值钱**：不修它，接线只会把误判写进生产。
> ④ 则是另一种形态：**补"前提的前提"**——写链栈（部署/签名/广播）在此前完全不存在，
> 没有它，存证与裁决出款两条腿永远只能是空实现/传 null。四个新增缺件（部署入口、W 自引用解、
> 地址落库、对拍向量）全部在"腿"能被接通之前完成。

> **审计后更正（2026-09-25）**：初稿的 A 级含 3 处误判（ET-49/GM-08/ET-16 的类只被自己的测试引用，
> 生产零引用、无注解装配），已降为 B；GM-31 的 A 成立但证据引错了类。**详见 §6 文档审计记录**。
与旧快照的"已落地 31 / 未做 84"相比，**不是功能变多了、而是判定标准变了**：旧版把"有类"就记 ✅（所以出现
`SilenceWindow` 这类零引用孤儿被算作已完成），本版把"**是否接线、用户能否用到**"单独拆出来。

**三条最关键的差距**（更正旧快照）：
1. ~~S1 无 Bot 接线~~ → **已不是缺口**；`/escrow` 命令链与 Mini App 接口都通。
2. ~~S6 无 Web 层~~ → **已不是缺口**；`TradeApiController`/`TradeInviteController` + `initData` 验签在位。
3. **S5 合约：非裁决路径 + Jetton 计价均已实现，缺口收窄但未闭合** → 状态机 + 非裁决资金路径（fund/deliver/confirm/refund）+ 回弹安全网 + **Jetton 入金/出账** + ET-50 校验均已实现（`acton test` **47 passed**，2026-09-30 复跑）；**仍未闭合**：存储费池额度、链上部署与真机验证（争议/多签裁决与⑥升级入口均已实现）。
   这是当前**唯一**横跨大量 ET 项的结构性阻塞（ET-09/10/11/15/17/19/21/50/51/53/59 等）。

**已知质量缺口（非规格缺项）**：
- ~~`LogSanitizer` 无盐哈希 + 仅 24bit~~ —— **判定更正：此项不成立**。源码实测 `LogSanitizer` 已实现带盐方法
  （`maskUserId(long, String salt)`，盐必填 + 48 bit）、`EvidenceHasher` 已实现 `saltedHash(...)`；
  **且已接线**（2026-09-29 复核：经 `UserIdMasker:78` → `BotWiring:122`，见 GM-33）。本项初稿照抄 SPEC 6.2 而未读源码，属我的误判；
- ~~`BannedWordMatcher` 逐词 `contains`（其 javadoc 自述"未实现 Trie"）~~ —— **2026-09-29 已换 Trie**：
  按折叠字符建树、沿文本每个起点走，复杂度从 O(词表 × 文本长) 降到 O(文本长 × 最长词长)。
  关键是**逐字保住**了「返回**词表顺序**最靠前的命中（而非文本位置最靠前的）」这条契约——
  每个终点只记最小词表索引；新增 4 条用例钉住（含顺序正反两例与 2000 词规模例）。
- ~~五个~~**四个**"已落地未接线"的类（`AdminRegistry`/`FeatureToggle`/`JoinOnboarding`/`ReplayGuard`；~~`ChannelPuppetGuard`~~ **已接线**——GM-12，2026-09-29）——
  每个都卡在缺件上（详见 `.rivet/HANDOFF.md` 的记录），**不是补一行调用能收口的**。

**验证状态**：代码面 `mvn -B clean verify` **1097 surefire + 8 IT 全绿**（2026-10-03 · 清理轮终跑；
控制台 per-module 口径：core 245 / federation 13 / escrow 375 / chain 114 / app 350 + app IT 8。
较 1214+5 的差值 = 删除 16 个类及其测试（约 117 用例）后 TON Connect 等波净增；IT 2 类 8 用例）。
上一版记录（2026-10-02 · S5 写链期终跑）：1214 surefire + 5 IT（core 301 / escrow 478 / chain 90 / app 332）。
**清理轮修复一处途中发现的测试断裂**：415443c 把验签器改为「URL 解码态 dcs」后，
`TradeInviteControllerTest`/`TradeApiControllerTest`/`EscrowChainTxControllerTest` 三个控制器的
initData 造数仍是旧口径（原态值），全量 34 用例红；已同步修复（提交 4b9afd7）。
**真机面未核销**（`docs/ONLINE-VERIFICATION.md` 的 A9/A10/A11/A19 与本波新增 **B10/B11/B12**：部署/存证/裁决出款，
障碍是缺 testnet 钱包 seed 与 GRAM——资源清单见 HANDOFF），
因此"用户可用"目前**只有本地证据、没有真机证据**——S5 写链层（部署/存证/出款）同样只验到
**离线段**（对拍向量/结构回读/替身接缝），真实链上行为未验。

## 5. 复算命令（本轮新增）

> 本文档的数字都应能由下面几条命令复现。**改完代码后请重跑并更新对应数字**——
> 本文档历史上正是因"数字靠手写"而与实测差得很远（见 §6 的自审记录）。

```bash
# 1) 基线
git rev-parse --short HEAD                          # 期望：本波提交（或更新）
find tgg-*/src/main -name '*.java' | wc -l          # main 类数 -> 181
find tgg-*/src/test -name '*.java' | wc -l          # test 类数 -> 124

# 2) 用例数（需可达的 MySQL + 一个非空 token；token 可为假值）
#    ⚠️ 必须先 clean：maven 增量编译会跳过「未改动但接口已变」的实现类，
#    用 stale class 混过编译（2026-09-29 实证：GroupAdminPort 加方法后
#    WarningOrchestratorTest.FakePort 缺实现却报过 BUILD SUCCESS）——
#    接口/签名变更后的验证一律 clean 重编再信结果。
TGG_DB_NAME=tgg_verify TELEGRAM_BOT_TOKEN=123456:dummy-for-it mvn -B clean verify

# 3) §2.5 的「零引用且无注解装配」清单
for f in $(find tgg-*/src/main -name '*.java'); do
  n=$(basename "$f" .java)
  c=$(grep -rn "\b$n\b" --include=*.java tgg-*/src/main 2>/dev/null \
      | grep -v "/$n.java:" | grep -vE ":[0-9]+: *(\*|//)" | wc -l)
  if [ "$c" -eq 0 ] \
     && ! grep -qE '@(Component|Service|Repository|RestController|Controller|Configuration|Entity|Bean|SpringBootApplication)' "$f"; then
    echo "$n"
  fi
done | sort

# 4) §2.3 的计数表（**必须限定在 §2 区域内**——否则会连 §6 审计表的 4 行一起命中）
awk -F'|' '
/^## 2\. 逐项实测/{s=1}
/^### 2\.3/{s=0}
s&&/^\| GM-[0-9]+/{gsub(/[* ]/,"",$4); c["GM" SUBSEP $4]++; t["GM"]++}
s&&/^\| ET-[0-9]+/{gsub(/[* ]/,"",$4); c["ET" SUBSEP $4]++; t["ET"]++}
END{for(k in c){split(k,p,SUBSEP); printf "%-4s %-2s = %d\n", p[1],p[2],c[k]}
printf "GM 合计 = %d\nET 合计 = %d\n合计 = %d\n", t["GM"], t["ET"], t["GM"]+t["ET"]}
' docs/CODE-VS-SPEC.md | sort
# 期望（2026-10-03 · L3 审查批次 ET-04/49 复核改判后由命令重算）：GM A24 B0 C3 D2 E2 X5；ET A42 B3 C22 D3 E1 X7；合计 114
# 注意：2026-10-03 起判定新增 X 列（已删除类），旧期望值（GM A18 B3 C8 D5 E2 / ET A26 B3 C38 D9 E2）已过时
```

**踩坑提示**：第 2 条的 token 是给 `BotTokenConfig` 用的——它在 **bean 创建期**就校验非空，
缺了会让上下文起不来（比 `@MockitoBean BotRunner` 更早发生）。数据库若报
`FlywayValidateException`（迁移脚本改动过而库未 repair），换 `TGG_DB_NAME=<空库名>`
即可从零迁移，无需动既有库。

## 6. 文档审计记录（2026-09-25）

审这份文档自身的方法与结论：

**审计方法**：抽出所有标 A/B 的行所引用的类（63 个），逐个实测**生产侧非注释引用数**
（`grep -rn "\b<类>\b" --include=*.java tgg-*/src/main | grep -v "<类>.java:" | grep -vE ":[0-9]+: *(\*|//)"`），
与行内判定对账。**可复算**——接手者请自行重跑，不要采信本节的结论。

**方法学盲点（重要）**：引用数为 0 **不等于**未接线——`TradeInviteController`（`@RestController`，:64）、
`AdminWordController`、`BotWiring`（`@Configuration`）都靠**注解**被 Spring 装配，文本引用为 0 是正常的。
审计必须**先查注解**再定论。

**审出的错误（已改正）**：
| 行 | 原判定 | 实测 | 现判定 |
|---|---|---|---|
| ET-49 | A | `TradeStage` 仅被 `TradeStageTest` 引用，main 零引用、无注解 | **B** |
| GM-08 | A | `MessageRepeatDetector` 仅被自己的测试引用；`MessageGuardOrchestrator` 内也无重复检测内联 | **B** |
| ET-16 | A | `EscrowVerdictGuard` 仅出现在测试 | **B** |
| GM-31 | A（证据引 `PermissionChecker`） | `PermissionChecker` 零生产引用；但能力在（`MemberRolePort` 11 引用 + 命令层管理员校验） | A（**证据已更正**） |

**审计确认成立的部分**：五个 B 项（`AdminRegistry`/`ChannelPuppetGuard`/`FeatureToggle`/`JoinOnboarding`/`ReplayGuard`）
与 `LogSanitizer` 实测均 0 引用且无注解 → B 判定成立。

**局限**：本次只审了 A/B 两类（引用数是可机械核实的维度）；**C 级 60 行已于 2026-09-29 逐行回读源码**（§6.2 五批），可信度已与 A/B 拉平——但两者都只到「存在性 + 接线」这一层，**行为级验证不在本文档范围内**。

### 6.1 重测记录（2026-09-29 · §2 全表按「引用 + 注解」机械复核）

**做了什么**：抽出 §2 两表里被引用的 113 个标识符，逐个实测「是否存在 / 生产侧非注释引用数 / 是否带装配注解」，
再对每行做「本行引用的类里，是否至少有一个被引用或带注解」的机械判定，与行内判定对账；
同时重跑 §2.5 的零引用脚本（**34 个，与上一版逐字一致**——说明脚本口径稳定），
并对 15 个 D 类行做「是否存在实现」的定向 grep（日志频道 / 群组同步 / 紧急暂停 / 代理合约 /
平台费 / TON Pay / 年龄门 / 备份 / 多签），其中只有 ET-50 被证伪。

**改了什么（3 处，全部有物理证据）**：

| 行 | 原判定 | 现判定 | 证据 |
|---|---|---|---|
| GM-08 | B（"仅被自己的测试引用"） | **A** | `MessageRepeatDetector` 由 `BotWiring:452` 装配、`MessageGuardOrchestrator:86/96` 持有——已进判定链 |
| GM-33 | B（"生产侧无任何调用方"） | **A** | `UserIdMasker:78` 调用 `LogSanitizer.maskUserId(...)`；`UserIdMasker` 由 `BotWiring:122` 注册为 bean 并注入 `TradeNotifier`（`:654`） |
| ET-50 | D（"未找到实现类"） | **C** | `contracts/EscrowContract.tolk:239` 断言 `in.senderAddress == ownJettonWallet`、抛 `Errors.NotTrustedWallet`（`types.tolk:16`）——与同文档 ET-10 行的说法此前**自相矛盾** |

**判为「机械误报」而未改的 2 处**：GM-05 的 `AdminRegistry`、ET-16 的 `EscrowVerdictGuard` 仍是零引用孤儿，
它们只是**同时**引用了已接线的 `MemberRolePort` / `EscrowVerdict`。这类「一行里既有接线件又有孤儿件」
是机械判定的盲区——故本轮**不以机械判定替代行文复核**，只把它当线索。

**顺带修掉的两处文档自身缺陷**：
1. §2.3 旧记录的复算命令 `awk -F'|' '/^\| (GM|ET)-[0-9]+/{print $4}'` **未限定区域**，会连本节的 4 行审计表
   一并命中（实测 118 行、合计被算成 118）。已在 §5 第 4 条改为区域限定版，本次计数即由它产出。
2. §2 表头写「113 条」而下表合计 114 → 更正为 114；并把「ET 无 63/74」的原因（SPEC 自身去重）写明。

**仍未覆盖的**：C 级 60 行的"缺件"描述**未逐行回读源码**（与上一轮同样的局限）；
E 级 4 行（GM-04 / GM-28 / ET-07 / ET-70）仍是"我没读到决定它的实现"的如实标注，本轮未新增证据。
**一句话**：A/B 两类现已是可机械复算的；C 类只保证「主体类存在」这一层。

### 6.2 C 级逐行回读（分批进行 · 2026-09-29）

> 在此之前，C 级 60 行的「缺件」描述只经过「类是否存在」这一层（见 §6 局限）。以下按主题分批**回读源码**。

**批 1｜合约 / 链上组（已完成）**——回读 `contracts/EscrowContract.tolk`、`contracts/types.tolk`、
`EvidenceHasher`、`DeliveryProofSubmission`、`BalanceCrossVerifier`、`ChainSource`、
`EscrowMultiSigRule`、`FederationPartySignatureVerifier`，并**实跑** `acton test`。

| 行 | 原描述 | 实测 | 处理 |
|---|---|---|---|
| ET-10 | "jetton 记账/出账**未做**" | 合约头与 81/117 行**已实现**入金+出账；`acton test` 有 4 条 jetton 用例 | **改**（§1 同款错误同步改） |
| ET-58 | "合约侧**待做**" | 合约头把 ET-58 列为已遵循的纪律；`refund rejected after release` 等用例即覆盖乱序越级 | **改** |
| ET-57 | "`EvidenceHasher` 侧**未核实**——不作断言" | 已回读：`saltedHash(String,String)` **盐必填**；合约侧未兑现 | **改**（补上断言） |
| 用例数 | "22 用例 / 22 passed" | `acton test` 实跑 = **26 passed / 0 failed** | **改**（§1 / §4 / ET-10 / ONLINE-VERIFICATION B1） |
| 用例数（2026-09-30 复算） | "26 passed" | `acton test` 实跑 = **47 passed**（35 存量 + 12 升级用例） | **改**（§1 / §4 / ET-10） |
| ET-15 | "Java 侧（链上未落）" | 规则在；但 `FederationPartySignatureVerifier` **生产零引用** | 补实测事实 |
| ET-17 | "IPFS 未接" | 成立（全仓无 IPFS 依赖，源码命中皆为注释） | 补实测依据 |
| ET-19 / ET-59 | "无真实链上源" | 成立且更硬：**`ChainSource` 无任何实现类** | 补实测依据 |
| ET-51 / ET-53 | "链上未接" | 成立；两个类的 javadoc 自述"合约侧尚未接收或计算该哈希" | 补精确锚点 |
| ET-52 | 概念落在 `deliveryProofHash` | 成立（`DeliveryProofSubmission` 是纯函数） | 补实现锚点 |

**批 1 的净效应**：C 级判定本身不变（各行仍是 C，只是描述更准），但**修正了 4 处事实错误**——
其中 ET-10 与 §1 的「jetton 未做」是**同一错误出现两次**，而它正是 §4「结构性阻塞」的论据之一；
该论据因此收窄为「只剩争议裁决与存储费池额度」。

**批 2｜GM 组（已完成）**——回读 `MuteSchedule`、`PhishingNotice`、`JoinVerificationFlow`、`SecondaryVerificationFlow`、
`JoinRiskScorer`、`RiskPolicy`、`AnomalyDetector`、`RateLimiter` / `RateLimitPolicy`、`SlidingWindowCounter`、
`KeywordAutoReply`，并追查 `BotWiring` 的装配点。

| 行 | 原描述 | 实测 | 处理 |
|---|---|---|---|
| GM-32 | C（"接入深度未核实"） | `BotWiring:436` 建 bean、`MessageGuardOrchestrator:82-94` 持有；默认值**全非零即默认启用** | **C→A**（本轮唯一判定变更） |
| GM-24 / GM-25 | "接入入群门禁的深度未核实" | `JoinRiskScorer` **生产零引用**；`RiskPolicy`/`RiskAssessment` 只被它引用 → **整链未接入** | **改**（把"未核实"落成结论） |
| GM-26 / GM-27 | 仅列类名 | 两者生产零引用；GM-27 的 `Verdict` 枚举只被同为孤儿的 `DeviceLinkAnalyzer` 复用 | **改**（补实测事实） |
| GM-01 | "解封**未核实**" | `GroupAdminPort` 只有 kick/ban/mute/deleteMessage——**确无 unban** | **改**（锐化） |
| GM-07 | "非 Redis" | 成立（全仓 `pom.xml` 无 redis 依赖） | 补依据 |
| GM-13 | "无生产调用点" | 成立；且是覆盖 GM-13 / ET-54 / ET-71 的合并库 | 补事实 |
| GM-02 / GM-15 / GM-17 | — | **核证准确**（`MuteSchedule` 纯值对象且零引用；`JoinVerificationFlow` 零引用；`KeywordAutoReply` 经 `BotDispatcher` 真接线） | **不改** |

**批 3｜ET 交易流程 / 准入 / 超时组（已完成）**——回读 `CreditScore`、`FraudLinkage`、`TradeGroupLifecycle`、
`EscrowGroupGuide`、`TradeMessageMapPort`、`TradeTimeoutPolicy`、`NewcomerBadge`、`TraderTier`、`SilenceWindow`，
并追查 `ModerationPersistenceConfig` / `ModerationOrchestrator` 的装配。

| 行 | 原描述 | 实测 | 处理 |
|---|---|---|---|
| ET-03 | "联动入交易准入的深度**未核实**" | `CreditScore` 生产零引用 → **未联动** | **改**（落成结论） |
| ET-04 | 仅列类名 | `FraudLinkage` 零引用；但执行侧 `ModerationOrchestrator` **确实存在且已被引用** | **改**（分清情报侧/执行侧） |
| ET-05 | 仅列类名 | 两件均零引用 | **改** |
| ET-06 | 仅列类名 | 端口 + JPA 实现 + 配置装配**都在**，但**无消费方** | **改** |
| ET-11 | "仅状态登记" | 核实成立（类内无任何 `Chain*` 引用） | 补锚点 |
| ET-13 | "惰性判定" | 核实成立（且给出应触发的资金动作） | 补锚点 |
| ET-35 | "`NewcomerBadge`（待决 B2）" | 该类实现的是「前 3 笔豁免互刷检测」，与「首单保障」不是一回事 | **改** |
| ET-37 | 仅列类名 | `CreditScore` 零引用；`TraderTier` 仅被同样零引用的 `Leaderboard` 引用 | **改** |
| ET-38 | 仅列类名 | 机制在（前 3 笔豁免），零引用 | 补事实 |
| ET-39 | "无生产调用点" | 核实成立 | 补状态机锚点 |
| ET-40 | 仅列类名 | **时段层已接线、交易群静默阶段未接线** | **改**（分层） |

**批 4｜ET 争议 / 投票 / 证据组（已完成）**——回读 `MemberVote`、`VoterRegistry`、`VoterRecusal`、
`VoteRewardAllocator`、`VoteStake`、`StakeThresholdAdjuster`、`FallbackSettle`、`EvidenceChannel`、
`EvidenceDeadline`、`DisputeStatementFlow`、`PenaltyPlanner`，逐个追查引用者。

**主要发现：整个裁决 / 投票簇只**互相**引用，对外没有任何入口。** `MemberVote` ← `FallbackSettle`、
`VoterRegistry` ← `MemberVote`、`VoterRecusal` ← `MemberVote:120`、`VoteStake` ← `StakeThresholdAdjuster`
——簇内成链，但没有任何交易流程调用它。另有六个类**连一处代码引用都没有**：`EvidenceChannel`（连 javadoc
提及都没有）、`VoteRewardAllocator`、`EvidenceDeadline`、`StakeThresholdAdjuster`、`DisputeStatementFlow`、`PenaltyPlanner`。

| 行 | 处理 |
|---|---|
| ET-41 / ET-42 | **改**：写明"簇内已接、对外无入口"，并给出互引链的实测锚点 |
| ET-43 / ET-44 / ET-45 / ET-46 / ET-47 / ET-60 / ET-61 | **改**：把"仅列类名"落实为"零引用 / 仅注释提及" |

**批 5｜ET 风控 / 信用 / 榜单组（已完成）**——回读 `Leaderboard`、`RankPrivacy`、`CounterpartyDiversityScorer`、
`MutualBrushDetector`、`DeviceLinkAnalyzer`、`NewAccountLimitPolicy`、`SuspiciousRelationPolicy`、
`CreditScore`、`TraderTier`，逐个追查引用者。

**主要发现：这一组同样整组孤立。** `Leaderboard`、`NewAccountLimitPolicy`、`SuspiciousRelationPolicy`
**全仓零引用**；`RankPrivacy` 只被零引用的 `Leaderboard` 引用；`CounterpartyDiversityScorer` /
`MutualBrushDetector` / `DeviceLinkAnalyzer` 只在 javadoc 里被提及；ET-67 的"不累加"落点确实**不存在**
——`CreditScore` 是纯函数，没有信用累加侧的入口。

| 行 | 处理 |
|---|---|
| ET-62 / ET-64 / ET-65 / ET-66 / ET-68 / ET-69 / ET-71 / ET-72 / ET-73 / ET-75 / ET-76 / ET-79 / ET-80 | **改**：落实测（零引用 / 仅注释提及 / 只被孤儿引用） |
| ET-67 | **改并结案**：原写"'不累加'的落点**未核实**"，本轮核实为**不存在落点** |
| ET-77 / ET-78 | **核证准确**，仅把 ET-78 的"同上"写明为"无月度独立实现" |

**五批总账**：C 级 **60 行已全部逐行回读**。净效应——判定变更 **1 行**（GM-32 的 C→A），
其余 59 行**判定不变、描述落到实测**；其中十余行是把「未核实」或「仅列类名」改成**可复算的结论**
（GM-01/24/25/26/27、ET-03/10/35/40/57/58/60/61/67 等）。
**仍未做的**：这五批确认的是「类是否存在、被谁引用、缺件是什么」，**不等于**做过行为级验证——
真正未验证的仍是 §4 末尾那条：真机面与链上部署。

## 7. Wave 1.2 决策记录（五个「已落地·未接线」项）

计划要求逐项给出"接上"或"明确不接 + 理由"，**不留悬空**。五个项现全部有结论：

| 类 | 决策 | 理由（实测/读源码后） |
|---|---|---|
| `ChannelPuppetGuard` | **接上**（对应 GM-12），但**必须先补 fail-safe 默认** | 其语义是"白名单为空即全拦"，而项目**刚吃过同类亏**：`LinkMatch` 空白名单曾导致"删光所有带链接消息"（修法见 `MessageGuardOrchestrator.isSuspicious`）。群里**以频道身份发帖正当且常见**，默认空白名单下直接接上=删光所有频道帖。触发路径须区分「未配置」（=不启用）与「配了但为空」（=全禁），与 LinkFilter 的修法一致。**✅ 已接（2026-09-29）**：`BotWiring.channelGuardOrNull` 用 `:#{null}` 默认值把「未配置」（→ 返回 null，判定不参与）与「配了但为空串」（→ 空集，全拦）分开；并**同波修掉前提缺口**——频道帖此前在入站构造期抛异常（`userId=0` 被拒），已由消息模型承载频道身份解决。判定序位定在**链接之前**（先报身份问题） |
| `AdminRegistry` | **已删除**（2026-10-03） | 原决策「不接」：与已生效的 `MemberRolePort`/`TelegramMemberRoleAdapter`（实时解析 creator/administrator）职责重叠；登记表需失效策略与同步机制。git 可找回（`git log --diff-filter=D -- 'tgg-core/src/main/java/com/tg/escrow/core/AdminRegistry.java'`） |
| `FeatureToggle` | **已删除**（2026-10-03） | 原决策「不接（待产品决策）」：其 fail-closed（未登记=关）对**保护类**功能是**反向**的（默认关=保护失效）。要接必须先定"哪些功能算可选"——属产品决策，非工程项。git 可找回 |
| `JoinOnboarding` | **已删除**（2026-10-03） | 原决策「不接」：缺"已引导过"的持久化（重启即忘→重复引导）；补它需新迁移 + 用户偏好表，成本高于收益。git 可找回 |
| `ReplayGuard` | **已删除**（2026-10-03） | 原决策「不接」：缺 nonce 源。唯一签名面是 Mini App `initData`，它在同一会话内被每次 API 调用重复携带，按 nonce 拒会**打断正常使用**。git 可找回 |

> **一句话**：1 项接（且有前置安全条件），4 项明确不接——四项的"缺件"都不是补一行能收口的，
> 且其中两项（`FeatureToggle`/`ReplayGuard`）的真正阻塞是**上游定义缺失**而非实现缺失。
> **2026-10-03 清理**：这 4 项不接的类已随「项目全面清理」删除（git 历史可找回，找回命令见各表行）。


### 7.1 Wave 1.3 孤儿批量处置（2026-09-29 · 拍板⑦「批量明确不接+记录」）

> 与 Wave 1.2 同法：每项给结论与理由，不留悬空。分三类处置——**不接**（与定案冲突或无上游）、
> **随③接线**（ET-04 渐进式落地时一并接）、**后置**（上游流程真实使用后再做）。

| 类 | 处置 | 理由（实测/与定案的关系） |
|---|---|---|
| `PhishingNotice`（GM-13/ET-54/ET-71 合并定义库） | **不接** | 定期推送属"真需定时"（项目非目标）；无触发它的流程。文案定义保留，防钓鱼改由 GM-22/23 的入群防线承担 |
| `JoinVerificationFlow`（GM-15） | **已删除**（2026-10-03） | 原决策「不接」：60s 超时踢出属"真需定时"；即时验证已由 `JoinSubscriptionGate`（订阅门禁）覆盖，降级形态可接受 |
| `SecondaryVerificationFlow`（GM-26） | **已删除**（2026-10-03） | 原决策「不接」：第二渠道验证是运营能力（人工回访类），无自动化装配需求方 |
| `AnomalyDetector` / `DeviceLinkAnalyzer`（GM-27） | **不接（暂）** | 信号无采集点（同 ET-04 判定）；随③信号源落位后重新评估是否作为第三信号源接入 |
| `NewcomerBadge`（ET-35/38/67） | **随③接线** | 「前 3 笔豁免互刷检测」是③（渐进式互刷信号）的一部分——信号落位时豁免逻辑一并接 |
| `MemberVote` / `VoterRecusal` / `VoteRewardAllocator`（ET-41/42/44） | **已删除**（2026-10-03，方案一） | 原决策「不接」：**与已定案 A0-2/C1 冲突**——裁决语义已定「链下多签裁决 + 联邦单签上链执行」，群内投票机制是另一套裁决语义。若将来推翻方案 A（改链上/群内投票裁决），**经 git 历史找回此簇**（`git log --diff-filter=D -- tgg-escrow/src/main/java/com/tg/escrow/escrow/MemberVote.java`） |
| `EvidenceChannel` / `EvidenceDeadline` / `DisputeStatementFlow`（ET-43/45/60） | **后置** | 证据双通道/24h 时效/双方陈述是独立用户面功能（新表 + 提交入口 + 确认已读流），改动面大；当前争议理由链下留痕已满足裁决最低需要。**待争议流程被真实使用后再做**——避免为接而接 |

> **一句话**：7 组孤儿全部有结论——3 组不接（无上游/非目标）、1 组与定案冲突不接、1 组随③接线、
> 2 组后置。C 级噪声清零后，CODE-VS-SPEC 剩余差距即真实开发面。
> **2026-10-03 清理**：其中 3 组不接/1 组冲突的类已随「项目全面清理」删除（见 §7.1.1 与各表行，git 可找回）。

### 7.1.1 争议/仲裁业务链定案与二次处置（2026-10-02）

**裁决语义层定案（用户拍板 · 方案 A）**：维持 `A0-2/C1`（联邦链下多签 + 链上联邦单签执行），
**不引入群投票裁决层**。理由：与既有拍板一致、无架构返工；需求自身的终局裁决人本就是联邦
（`SPEC §6.1`「投票人数不足时移交联邦仲裁员」）；合约 `Resolve` 只认联邦 sender。
（若日后推翻方案 A，方案 B 的「双层裁决」仍可复活——见 `docs/archive/plans/争议-仲裁业务流程链定义与孤儿接线.md`。）

**本波实际接线（Wave 1/2，提交 `2be11aa` / `3f2fa7f`）**：`DisputeStatementFlow`(ET-60)、
`EvidenceDeadline`(ET-45)、`EvidenceChannel`(ET-43，链上腿显式关)——三件由「零引用孤儿」变为
**生产可达**（`/escrow statement` / `/escrow read` / `/escrow evidence`，会话落新表 `escrow_disputes`，迁移 V7）。

**逐条处置（9 点名孤儿 + 投票机件簇 —— 不留悬空）**：

| 类 | 处置 | 依据 |
|---|---|---|
| `DisputeStatementFlow` (ET-60) / `EvidenceDeadline` (ET-45) / `EvidenceChannel` (ET-43) | **已接线** | 本波 Wave 1/2 |
| `PenaltyPlanner` (ET-61) | **已接线**（Wave 3，提交见下） | 败诉台账（V8）+ 跨群警告 → 计划；败诉口径经用户 2026-10-02 拍板（outcome 不利方） |
| `SuspiciousRelationPolicy` (ET-68) / `StakeThresholdAdjuster` (ET-46) / `MemberVote` / `VoterRegistry` / `VoteStake` / `VoterRecusal` / `VoteRewardAllocator` / `FallbackSettle` | **已删除**（2026-10-03，方案一） | 原决策「维持不接」：与 `A0-2/C1` 裁决语义冲突。复活路径 = git 历史（`git log --diff-filter=D -- tgg-escrow/src/main/java/com/tg/escrow/escrow/`） |
| `NewAccountLimitPolicy` (ET-66) | **阻塞** | 账号年龄不可得（前提缺件，`HANDOFF` 已记） |
| `MuteSchedule` (GM-02) | **已删除**（2026-10-03） | 原决策「维持不接」：到期由 Telegram 服务端保证；仅审计视图缺口 |
| `SecondaryVerificationFlow` (GM-26) | **已删除**（2026-10-03） | 原决策「维持不接」：运营能力（人工回访），无自动化装配需求方 |

**新增配置键**（已登记 `application.yml` + `README.md` 键表）：
`tgg.dispute.evidence-window`（`TGG_DISPUTE_EVIDENCE_WINDOW`，默认 `PT24H`）、
`tgg.dispute.require-statements`（`TGG_DISPUTE_REQUIRE_STATEMENTS`，默认 `false`）。
