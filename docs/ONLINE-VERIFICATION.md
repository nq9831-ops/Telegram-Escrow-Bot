# 线上统一验证清单（本地不可得，后期集中验证）

> 用途：本地开发中标注「未验证」的事项全部收口于此，**线上部署阶段逐条核销**。
> 核销纪律：每条给出「怎么验 / 期望观察」；验过打勾并注日期，验不了的写明障碍。
> **执行序列见 `docs/LIVE-TEST-RUNBOOK.md`**（本文是状态表；「按什么顺序跑、每步命令」在那边——每跑完一项回来勾本表对应行）。
> 建档：2026-09-24（天梁）· 配套：`docs/requirements/SPEC.md` 第 8 节部署指引。
> **首次线上部署：2026-09-24，服务器 141.164.50.75（www.7kyuedu.xyz）。**
> **第二次部署（同日）：T2 查询 + cancel + 乐观锁上线，见文末「第二次部署实录」。**
> **第三次部署（2026-09-29，增量补丁）：安全门禁 + ChainGateway/Adnl + 重复检测上线；C7/C8/C9 真机核销，见文末「第三次部署实录」。**
> **✅ 已部署（2026-09-29 · 第四次部署）**：`f089ac0` 的三波改动已上线（`app.jar` 83,280,873 字节；含
> `/escrow rank`、`/escrow credit`、频道傀儡防御、入群风控、Trie 加速、`AllowedCurrencies`），
> `env.sh` 已加 `TGG_ALLOWED_CURRENCIES=USDT`。详见文末「**第四次部署实录**」。
> **仍需真机客户端 / 真实群才能核销**：A12 / A13 / A14 / A15，以及 C10 / C11 / C12 的**行为面**
> （命令与风控是否按配置生效）——本轮只核销到「部署健康」与「未配置 = 不启用」。
> **✅ 已部署（2026-09-29 · 第六次部署）**：`ce83ab0` 的开发侧功能收口已上线（`app.jar` 83,286,232 字节；含
> ③ create 预览币种校验、④ 命令级脱敏日志、② REVIEW 复核通知通道）。详见文末「**第六次部署实录**」。
> **频道防御与入群风控默认不启用**，要开启只需改 `env.sh` 再重启（**无需**重新部署 jar）。

## A. 依赖真实 Telegram（A2 Bot Token 解锁）

> ⛔ **2026-10-03 范围变更**：**A5 / A10 / A11 / A15 / A18 / A23** 对应的功能（违禁词处置 / 订阅门禁 /
> 爆发保护 / 风险评分 / 解封禁言命令 / 管理留痕）已随群管理**整体移除**——这些核销项**作废**
> （行保留供审计）。其余 A 项（命令回执 / 交易链 / Mini App / 回复式命令等）不受影响。

| # | 事项 | 怎么验 | 期望观察 | 状态 |
|---|---|---|---|---|
| A1 | S1 用户级验收：命令回执 | 私聊 bot 发 `/escrow create 2002 100 USDT` → `/escrow confirm …` | 风险提示 + 「已创建订单 #N」 | ✅ 2026-09-24（订单 id=1 落库，state=OPEN） |
| A2 | `TelegramBotHandler` 运行期（发送/长轮询） | 启动应用 → 发任意命令 | 回执到达；无 callback 时无转圈 | ✅ 2026-09-24（`BotSession: deleteWebhook: true`、`Started EscrowBotApplication`；回执到达） |
| A3 | **隐私模式关闭后留痕生效** | BotFather 关隐私模式→移除→重入群→群里发普通消息 | Bot 日志可见该消息（漏配则留痕静默失效，09 材料） | ☐ 未验——需 BotFather 关隐私模式并重入群（私聊场景不触发） |
| A4 | answerCallbackQuery 不变量 | 点任意 inline 按钮 | 按钮不转圈（finally 应答） | ☐ 未验——inline 按钮交互属 S3，尚未接线 |
| A5 | GM-06 违禁词命中处置（删消息+警告） | 发违禁词 | 消息被删 + 警告回执（需 Telegram 删除 API，线上接） | ☐ 未验——`GroupAdminPort` 真实适配器未接 |

## A'. 交易命令端到端（第二次部署引入的能力；A12 / A13 为 Wave 1–3 的待部署项）

| # | 事项 | 怎么验 | 期望观察 | 状态 |
|---|---|---|---|---|
| A6 | **`/escrow cancel` 端到端** | 私聊发 `/escrow cancel 1` | 回执「已取消」且**库里 state 变 CANCELLED** | ✅ 2026-09-24 实测：`escrow_orders` id=1 → `state=CANCELLED`、`version=0→1`、`reason=当事人取消` |
| A7 | **乐观锁在生产库生效** | 同上（观察 version 列） | 写入后 `version` 自增 | ✅ 2026-09-24：`version 0 → 1`（证明 `@Version` 在真库上工作） |
| A8 | `/escrow status` 生产回执 | 私聊发 `/escrow status 1` | 回订单号 + 状态摘要 + 下一步 | ⚠️ **部分验证**：同链路（dispatcher→handler）已由 A6 端到端证通，且本地 `TradeCommandHandlerTest` 覆盖三条 status 用例；但**生产回执未亲眼确认**（status 只读，DB 不留痕，用户未回报文） |
| A9 | **对手方主动通知** | 甲私聊 bot 走 `/escrow confirm` → `/escrow lock`，乙（卖方）应在私聊收到通知；再让乙发 `/escrow deliver`，甲应收到 | 对方收到通知；**命令路径**在对方未与 bot 会话过时，发起方回执追加「对方可能收不到通知」；**Web 路径**（Mini App）同情形下只返回 `notified:false`、不产生文案（前端据此提示） | ✅ **已核销（2026-10-01 真机 · 本地实例 + bot @nq9831ops_bot）**——见文末「第七次核销实录」A9：双账号实测，甲 `lock` → 乙私聊收通知、乙 `deliver` → 甲私聊收通知；且**送达时不误报**「对方可能收不到通知」。服务日志两个不同脱敏 actor 证实双账号被正确区分。**未真机验**：降级分支（对方未与 bot 会话过 → 追加提示文案） |
| A10 | **入群订阅门禁（GM-16/T60）** | 配置 `TGG_JOIN_REQUIRED_CHANNELS`（须落在 `TGG_JOIN_ALLOWED_CHANNELS` 白名单内），再让一个未订阅者入群 | 未订阅者被移出，且**群内回执是群视角**（「新成员因未订阅 X 已被移出」——不是对被移出者说话，他看不到）；已订阅者正常收到欢迎语；**查询失败时应既不踢也不欢迎**（回"无法确认"） | ☐ **未验**——门卫与适配器已由 `JoinSubscriptionGateTest` / `TelegramChannelMembershipAdapterTest` 覆盖，但真实 `getChatMember` 与真实踢人无 token 无法验 |
| A11 | **入群爆发自动保护** | 配 `TGG_PROTECTION_MAX_JOINS`（如 5）与窗口/冷却，然后在群里连续拉超过该数量的人 | 超过上限后自动开启保护模式（后续入群者被移出）并给出可读原因；冷却期满且未再爆发则自动解除（惰性判定，无需调度器）；**未配置 max-joins（默认 0）时完全不检测** | ☐ **未验**——爆发判定与惰性解除已由 `JoinBurstGuardTest` 覆盖（并做过变异验证），链路已由 `TelegramBotHandlerTest.acceptanceJoinBurstProtectsGroup` 覆盖；但真实拉人、以及**冷却期自动恢复所需的真实时间流逝**，无 token 均无法验 |

| A12 | **`/escrow rank` 榜单端到端** | 发 `/escrow rank`、`/escrow rank 5`、`/escrow rank abc`、`/escrow rank 99` | 有效调用回「本期交易排行」+ 条目（等级徽标 + 评分），**不含任何明文用户 ID**（脱敏形如 `1***1`）；非法条数给可读提示（非数字 / 超范围）；**无任何成交史时**回「暂无数据」而非空白 | ✅ **已核销（2026-09-29 真机）**——见文末「真机核销回执 ①」：标题、等级徽标、**脱敏形态 `2***2`**、**无明文 ID**、同分按 ID 定序逐条对上。**未覆盖**：非法条数（`rank abc` / `rank 99`）与空榜两条分支 |
| A13 | **`/escrow credit` 个人信用摘要** | ① 发 `/escrow credit`；② 发 `/escrow credit 123456`（带**他人** ID） | ① 回自己的摘要：评分 + 等级徽标（附中文等级名）+ 完成笔数/收到评价数 + **五维拆解**（完成笔数 / 争议率 / 好评率 / 对手集中度 / 活跃时长）；**无评价时标注「暂无评价」**；（当 `完成笔数 == 0 && 收到评价 == 0` 时）追加一句如实说明——措辞只陈述事实与一般机制（「完成笔数与好评率均为 0；反向维度在数据为零时按满分计」），**不声称分数由哪几个维度构成**；② 明确拒绝（不含他人分数、徽标、ID —— 榜单匿名不能从这里绕开） | ✅ **已核销（2026-09-29 真机）**——见文末「真机核销回执 ②③」：主路径（评分 + 徽标 + 五维拆解 + 暂无评价标注）与**拒绝路径**（他人 ID → 只查自己、零泄漏）均实测通过；分数构成经手算复核为 25，与回执一致。**并因此暴露一处文案边界缺陷**（措辞声称"两个反向维度满分"而实际只一项）——已修 + 加用例钉住 |

| A14 | **频道帖（以频道身份发言）不再被整条跳过** | 在群里用**频道身份**发一条消息（或让管理员以匿名身份发言）：① 未配置 `TGG_GUARD_ALLOWED_CHANNELS` 时该帖**照常放行**（行为与从前一致，**且不再消失**）；② 配了白名单且该频道不在名单内 → **被删除**，回执说明是频道身份问题，且**不累计个人警告**；③ 该帖里若写 `/escrow …`，**不执行业务命令** | ✅ **已核销（2026-09-29 真机，第六次部署后）**——频道身份发 4 条普通消息（1/测试消息/111/1111）**均未被删**（白名单未配置 = 不启用 = 放行），2 条频道帖 `/escrow rank` **零回执且服务器日志零命令记录**（`BotDispatcher` 未路由业务命令，双侧证实）。本地覆盖见左；真机补测场景：配了白名单后的放行/拦截两侧 |

| A15 | **入群风险评分（GM-24/25）真机行为** | 配 `TGG_RISK_WEIGHTS`（如 `NO_USERNAME=30`）+ 阈值后，让一个**未设 @username** 的账号入群：① 阈值设成 REJECT（如 `TGG_RISK_REJECT_THRESHOLD=30`）→ 该成员**被移出**、群内回执写明"未通过入群安全校验"；② 阈值只到 REVIEW（reject=50）→ **照常欢迎、不移出**（只留痕，不公开标记）；③ **不配** `TGG_RISK_WEIGHTS` → 与从前完全一致（照常欢迎、不评分） | ✅ **REJECT 档已核销（2026-09-29 真机）**——测试窗口配 `TGG_RISK_WEIGHTS=NO_USERNAME=30,LANGUAGE_NOT_ALLOWED=30` + `TGG_RISK_REJECT_THRESHOLD=30` + `TGG_RISK_ALLOWED_LANGUAGES=zz`，小号重进群即被**真实移出**，回执逐字「⚠️ 新成员未通过入群安全校验，已被移出。」；留痕 `分数=30 命中={LANGUAGE_NOT_ALLOWED=30}`（ID 脱敏）。测毕配置已恢复 + 重启。**未真机单测**：REVIEW 档（只留痕不踢）与 ALLOW 档——离线侧已穷举（`JoinRiskScorerTest` 19 / `JoinSignalsTest` 4 / `MemberJoinHandlerTest` 17 / `TelegramBotHandlerTest` 19） |

| A16 | **交易群绑定 + 规则公告置顶（ET-05/06）** | 按 `/escrow guide` 的三步建群（用户建超级群 → 拉对手方与 bot → **群内**发 `/escrow create <卖方ID> <金额> <币种>` + `confirm`） | 群内 confirm 后回执含「交易群：已绑定本群，规则公告已置顶」；群内出现规则公告（含订单号与 `/escrow dispute` 指引）**且被置顶**；私聊落单则回执**不带群提示**（交易群可选，不噪声） | ✅ **已核销（2026-10-01 真机）**——见文末「第七次核销实录」A16：群内 `confirm` 后群公告「📢 本群已绑定为订单 #3 的交易群，全程留痕…」+ 回执「已创建订单 #3 / 交易群：已绑定本群，规则公告已置顶」。该文案**非假装**（`TradeCommandHandler` 仅在 `announce()` + `pin()` 均成功后返回）。**私聊不噪声**亦核销（订单 #1/#2 私聊回执无任何群提示）。**注意**：Bot API 无 bot 建群/拉人方法（telegrambots-meta 10.3.0 实测无此类），群只能由用户创建后拉 bot 入群 |
| A17 | **静默期 + 惰性归档（ET-39/40）** | 群内落单走完 `lock → deliver → release`（或 `refund`/`cancel`）；然后等静默期满（`TGG_TRADE_GROUP_SILENCE_DAYS`，缺省 7 天）后在群里发任意消息 | 放款回执含「交易群：已进入静默期（期满自动归档）」且群内有静默期公告；静默期满后群里**下一条消息**触发归档：群内出现归档公告、bot 退群、回执「交易群已归档（静默期满）」；**静默期内**发 `/escrow dispute` 则公告「恢复留痕」（群回 RECORDING） | ✅ **已核销（2026-10-01 真机）**——见文末「第七次核销实录」A17：① 放款回执含「交易群：已进入静默期（期满自动归档）」+ 群内静默期公告，DB `trade_groups` 落 `state=SILENT`；② 静默期满后一条群消息触发归档：群内出现「📢 交易 #3 的静默期已满，本群归档。bot 将退出本群，如需继续请新建交易。」+ bot 退群。⚠️ **加速办法更正**：原写「可临时把 `TGG_TRADE_GROUP_SILENCE_DAYS` 调成 0 加速核销」**不可行**——`=0` 使应用**启动即失败**（`TradeGroupService` 构造期拒绝 `PT0S`；`BotWiring` 用 `Duration.ofDays(long)`，最小 1 天）；实测可行的加速法是**把库中 `silence_started_at` 前推**超过静默期。⚠️ 归档时曾触发一处缺陷（**退群后仍往该群发回执** → 403 `bot is not a member of the supergroup chat`，异常落到线程池且污染日志），已修（提交 `386a524`）。**未真机验**：静默期内发 `/escrow dispute` → 公告「恢复留痕」（群回 RECORDING） |

| A18 | **解封 / 解除禁言命令（GM-01/02）真机核销** | 群内以管理员身份：先 `/ban <用户ID>` 造一个被封者再 `/unban <用户ID>`；先 `/mute <用户ID> 10` 造一个被禁言者再 `/unmute <用户ID>` | `/unban` 回「已解封 <ID>」且该用户**可重新入群**；`/unmute` 回「已解除禁言 <ID>」且该用户**立即可发言**（不必等禁言到期）；非管理员或目标角色不低于自己 →「无法…」可读拒绝；缺参 / 非数字 → 用法提示 | ✅ **已核销（2026-10-01 真机）**——见文末「第七次核销实录」A18：四条回执逐字「已封禁 8709189795」「已解封 8709189795」「已禁言 8709189795（10 分钟）」「已解除禁言 8709189795」；**行为层**：解除后该用户在群里发普通消息正常显示（未被拦截）。**未真机验**：非管理员 / 目标角色不低于自己的拒绝分支 |

| A19 | **榜单公开 opt-in / opt-out（ET-80）** | 发 `/escrow publish` → `/escrow rank`；再发 `/escrow unpublish` → `/escrow rank`；另用**未设 @username** 的账号发一次 `publish` | ① 未公开时榜单脱敏（形如 `1***1`）、不出现用户名；② `publish` 后回执含「@<username>」，榜单对该用户显示 `@<username>`（且该用户不再出现脱敏 ID）；③ `unpublish` 后恢复脱敏；④ 无 @username 的账号 `publish` → 提示先去设置（**不落成无效开关**，榜单仍脱敏） | ☐ **未验**——需真机客户端。<br>本地已覆盖：`TradeCommandHandlerTest` 4 用例（默认脱敏 / publish 公开 / 无用户名被拒 / unpublish 恢复）+ `WeeklyBoardPushTest` 1 用例（推送榜同口径）+ `ModerationPersistenceTest` 2 用例（V9 落库往返）|

| A20 | **命令菜单（输入 `/` 弹出的列表，setMyCommands）**（2026-10-03 新增） | 部署带真实 token 的实例（启动日志应见「命令菜单已注册（default scope，3 条）」「命令菜单已注册（管理员 scope，11 条）」），然后在 Telegram 客户端：① 私聊 bot 输入 `/`；② 在**群内**以管理员身份输入 `/`；③ 点菜单里的 `/escrow` 与 `/kick` 各一次 | ① 私聊菜单弹出 3 条：`/escrow`（担保交易：发起 / 推进 / 查询 / 信用榜单）、`/help`、`/start`；② 管理员在群内菜单弹出 11 条（上述 3 条 + `/kick /ban /unban /mute /unmute /del /warn /unwarn`）；**普通成员**在群内只看到 3 条（scope 分层不叠加的预期形态）；③ 点选后命令文本自动填入输入框、发送后由机器人正常处理。**失败面**：注册失败只留 warn（「命令菜单注册失败（…scope）」）、不阻断启动——bot 其余功能不受影响；菜单未出现时先查该日志 | ☐ **未验**——setMyCommands 是 Telegram 服务器端状态，必须真实 token + 客户端观察。（离线侧：`BotMenuTest` 4 用例钉内容与 Telegram 硬约束、`BotMenuRegistrarTest` 4 用例钉两个 scope / 失败不抛 / 幂等） |

| A21 | **Mini App 动作按钮按角色过滤（order-actions 预检）**（2026-10-03 新增） | 部署后打开一笔**已接受**订单的 Mini App 资金区：① 买方连钱包 → 动作按钮应为「支付入金 / 确认收货 / 发起争议 / 请求退款」（**不应出现**「确认交付」）；② 卖方连钱包 → 应为「确认交付 / 发起争议 / 请求退款」；③ 未部署合约的订单 → 无按钮、显示「订单尚未部署链上合约——暂无可执行的链上操作。」；④ 已结束订单 → 无按钮、显示「交易已结束，无链上操作。」 | 各角色看不到不该出现的按钮；未部署/终态时给出明确说明而不是可点按钮。**过滤刻意不含中间状态**（链下状态是流程登记、非链上镜像——按它硬收窄会误藏合法按钮；最终裁决在合约，见 `EscrowChainTxController.orderActions` 的维度说明；拉取失败时保持全显示、由服务端兜底） | ☐ **未验**——需真机（连钱包 + 真实订单）。离线侧：`EscrowChainTxControllerTest` 2 用例（角色矩阵 / 终态 / 未部署 / 陌生人 403）+ 过滤逻辑 Node 5 场景实测（buyer / seller / 未部署 / 终态 / 拉取失败）|

| A22 | **回复式指定目标（免输数字 ID）**（2026-10-03 新增） | 真机：① 管理员**回复某人的消息**后发 `/kick`（不带 ID）→ 该成员被踢、回执确认其 ID；② 回复一条消息后发 `/del`（不带消息号）→ 被回复的那条消息被删；③ 在群里**回复对方的消息**后发 `/escrow create <金额> <币种>` → 风险预览引导"回复同一条消息发 confirm"、再照做 → 订单创建成功；④ 显式 3 参形态（带 ID）同时回复了某人 → 仍以显式 ID 为准 | 回复链路在真机 Telegram 数据（reply_to_message）下工作；被回复者是 bot/频道时不误当目标（退化为缺参提示）；被回复者身份不可知时（频道帖）命令不越权 | ☐ **未验**——需真机。（离线侧：`IncomingMessageTest` 2 / `TelegramBotHandlerTest` 1（映射 + "不把 bot 当人"反证）/ `ModerationCommandHandlerTest` 3（含显式优先）/ `TradeCommandHandlerTest` 2 / `UserInteractionIT` 1（真实装配端到端））|

| A23 | **管理留痕频道 + 群名显示（GM-20/21）**（2026-10-03 新增） | 配置 `TGG_MODERATION_LOG_CHANNEL_ID=<私密频道ID>`（bot 需在该频道有发帖权限）后：① 在群里 `/ban <用户ID>`（或回复某人发 `/kick`）→ 审计频道收到「🛡 管理留痕：群 <群ID>（<群名>）｜封禁｜操作者 X｜对象 Y」；② 警告触线自动禁言 → 同样留痕；③ 不配频道（默认 0）→ 零推送；④ 群名未知时回退裸群 ID（不带空括号） | 留痕到达私密频道、动作/操作者/对象可按字核对；群名展示（bot 有 getChat 权限时）；**推送失败不影响处置本身**（旁路语义，只 warn 不抛） | ☐ **未验**——需真机（真实频道 + bot 发帖权限）。离线侧：`ModerationOrchestratorTest` 4（成功留痕/守卫拒零留痕/端口失败零留痕/联邦旁路可区分）+ `TelegramModerationAuditAdapterTest` 6 + `TelegramGroupTitleCacheTest` 6 |

## B. 依赖真实链（S5 合约 + C1 定案 + D 核实）

| # | 事项 | 怎么验 | 期望观察 | 状态 |
|---|---|---|---|---|
| B1 | `EscrowContract.tolk` 编译 | `acton build` | 编译通过 —— ✅ 2026-09-25 接入 Acton；`acton build` 与 `acton test` 均通过。**2026-09-29 复跑实测：`acton test` = 26 passed / 0 failed**（最初接入 2 passed；随后状态机/资金路径/回弹/ET-50/**Jetton 入金出账**逐步增至 26） | ✅ |
| B2 | Gas 基线报告 | `~/.acton/bin/acton test --snapshot gas-baseline.json`（对比用 `--baseline-snapshot`） | 出基线，后续版本对比。⚠️ 原写 `--gas-profile` **该 flag 不存在**（实测），已更正 | ✅ 2026-09-25 已生成 `gas-baseline.json`（**33613 B**；最初 22731 B，随用例 19→22 及存储引用化改动增长） |
| B3 | Jetton 计价托管（ET-50 校验 + 入金 + 出账） | `acton test` 的 jetton 用例 | 按 TEP-74 实现：入金认**我方钱包**发来的 `transfer_notification`（0x7362d09c），守卫链＝发送者==我方钱包（`NotTrustedWallet`）→ 资产==JETTON（`WrongAsset`）→ 报文 `sender`==买方（`NotBuyer`）→ 同资产量纲比额（`InsufficientFunds`）→ 附足量 TON 燃料（`InsufficientFeeTon`）；**出账**向**我方钱包**发标准 `transfer`（0xf8a7ea5），报文指定收款方，并有测试断言出站消息的 dest/body | ✅ 2026-09-25（Acton 模拟网；**链上未验证**） |
| B3 | **乱序消息不越级迁移**（ET-58） | 合约测试：confirm/deliver 同时发 | 状态机不越级（SPEC S5 验收） | ☐ |
| B4 | 交付证明哈希加盐（ET-57） | 提交常见短语 proofHash | 不可字典反推 | ☐ |
| B5 | 链上事件/资金验证（ET-19/59 + S4） | `ChainSource` 多源对账（`BalanceCrossVerifier`） | 交叉验证一致（D：TON 确认数语义须先核实）。⚠️ **现状核查（2026-09-25）**：原写的 `HttpChainSource` **在源码中零匹配、该类型不存在**（已用 grep 全仓核实），此行原为陈旧引用，已更正为实际存在的类型。且二者**目前均无生产实现、无调用方**（链侧尚未接线）；jetton 钱包地址推导已实现：判定层 `JettonWalletAddressResolver`（已离线测）、provider 两条路线——`AdnlJettonWalletQuery`（纯 Java、**当前的生产装配**，见 C8）与 `TonlibJettonWalletQuery`（需原生 `libtonlibjson`，作为"能提供原生库"的备选），两者**均未经真实节点验证**；消费入口为 `ChainGateway` 与 `/admin/chain` 端点（见 C8） | ☐ |
| B6 | 合约审计（ET-20） | 第三方审计 | 审计报告归档 | ☐ |
| B7 | **升级写链栈真机链路（⑥ 遗留修复）**：Propose→冷静期→Apply 全流程 | testnet：配 `TGG_CHAIN_UPGRADE_WALLET_SEED`（联邦钱包 v5r1 seed）后 `POST /admin/upgrade/propose`（带多签签名集）→ 等 72h → `POST /admin/upgrade/apply` | propose 回执 `chainExecuted:true`；`GET /admin/upgrade/status` 可见提案（codeHash+proposedAt）；72h 后 apply 成功且**合约 code hash 变为新代码 hash**；非联邦签名集被 400 拒 | ◐ **链上消息层已核销（2026-10-02）**：propose（真实 code 提案）→ `upgradeStatus` 读出（非零）→ cancel → 归零，全链真机通过（`S5LiveJettonUpgradeProbeTest`）。**apply 未验**：72h 时间锁是合约常量，演练无法等待——留待真实升级窗口。**HTTP 端点面**（多签守卫+回执）离线已覆盖（`AdminUpgradeEndpointAcceptanceTest` 6），真机组合待应用实例部署 |
| B8 | **签名载荷口径核对**（`AdnlChainSender` 类注释「签名载荷口径（推定，真机核对项）」） | 真机发一笔升级消息（或任意写链消息），观察联邦钱包是否接受签名。**先排除 wallet_id 不一致**：`TGG_CHAIN_UPGRADE_WALLET_ID` 必须等于钱包实际 id（v5r1 的 id 是 `((1<<31)|(workchain<<23)|version) XOR subwallet` 派生值，不是固定常量；误配同样表现为拒收） | 钱包接受签名并转发内部消息。**若真机验签失败，优先怀疑口径**：推定是「签 `createExternalTransferBody(config).hash()`（32 字节 cell hash）」，备选是「签 body 原字节」——改 `AdnlChainSender.sendToContract` 的 `signer.sign(...)` 一行即可切换 | ✅ **2026-10-02 核销（B10 首笔写链）**——**口径定案**：签 `hash(signingMessage)`（"sign" magic + walletId + validUntil + seqno + outActions，**不含签名**）+ detached 64 字节。**原「推定」已证伪**：ton4j 高层组装（`createExternalTransferBody`）为另一种版本钱包的格式（magic "send"，钱包静默拒收）——整体弃用，改为 `WalletV5R1MessageBuilder` + 官方 SDK 金标准对拍（提交 3a216b6） |
| B9 | **`upgradeStatus` 栈序核对**（`AdnlUpgradeStatusQuery` 类注释「栈序口径」） | testnet 配好后 `GET /admin/upgrade/status`（或直接 `acton` 读合约 get 方法） | 返回的 `proposedAt`/`codeHashHex` 与合约实际值一致。**若两值互换**（无提案却有 proposedAt），改 `AdnlUpgradeStatusQuery.statusFromValues` 的 tos 顺序一处即可 | ✅ **2026-10-02 核销（真实提案）**——**原推定被证伪，口径已修正**：实测 **tos[0]=codeHash（256 位大数 766CE1EC…）、tos[1]=proposedAt（unix 秒 1790940921）**——返回顺序即栈序。`AdnlUpgradeStatusQuery.statusFromValues` 已对调并回写注释；回归钉 `AdnlUpgradeStatusQueryTest`（2 用例）。cancel 后归 (0,0)（`S5LiveJettonUpgradeProbeTest`） |
| B10 | **S5 托管合约部署真机**（2026-10-02 新增） | **第 0 步（必做，防"签名被拒"误诊）**：用 `ChainWalletKeys.v5r1WalletAddress`（或直接跑 `ChainWalletKeysTest` 的 golden 流程）以所配助记词/seed + `wallet-id` 推导钱包地址，与 acton/钱包客户端显示的地址**逐字符对拍**——不等即 wallet-id 或私钥配错，先修再谈部署（v5r1 标准：testnet subwallet0 的 wallet-id=2147483645，官方公式见 `ChainWalletKeys.v5r1WalletId`）。然后 testnet：配 `TGG_CHAIN_UPGRADE_WALLET_MNEMONIC`（或 `..._WALLET_SEED`）后对一笔已存在订单 `POST /admin/chain/deploy`（body：orderId / buyerAddress / sellerAddress / federationAddress / asset / chainAmountNano / deployValueNanoton） | ① 回执 `ok:true` 且 `contractAddress` 不空；② actonscan（testnet）可见该地址的部署交易；③ jetton 单（asset=1）回执 `jettonWalletSet:true` 且地址可被 `GET /admin/chain/own-jetton-wallet?contract=<新地址>` 复推；④ **同参数重复调用不报错**（幂等重发；库中地址不变）。**本项首笔写链同时核销 B8（签名载荷口径）** | ✅ **2026-10-02 核销**：合约 `kQAnbsVohZ0FmcDNrXfcDdXKv8MzsicRRlQdDcZvmPAvTQFk` 从 uninitialized → **active**（余额 0.2999 GRAM，Last Tx 在链；acton 识别出合约 ABI）；**联邦钱包冷启动一体部署成功**（同一笔消息：钱包 uninit→active + 合约部署）。途中修复 4 个真机专属缺陷：签名参序 / detached 截取 / seqno -256 冷启动 / ton4j 格式代差（提交 3a216b6） |
| B11 | **链上存证真机**（ET-43/51） | testnet：订单已部署（B10）后走 `/escrow evidence <订单号> <文本>`（或经 `ChainGateway.recordEvidence`） | ① （SDK 直查或 get 方法）合约 `evidenceOf(hash)` 返回 found=1 且时刻非零；② 命令回执「链上存证：已上链」（未部署订单则应如实「未成功」）；③ 同哈希重复提交幂等（`evidenceCount` 不变） | ✅ **2026-10-02 核销**：`RecordEvidence` 上链（sendToContract 路径），读回 `evidenceCount = 1`、`evidenceOf(194729819052817) = (1, 1790939777)`——证据哈希真实落在合约 map 中 |
| B12 | **裁决出款 Resolve 真机** | testnet：订单合约有余额（部署附着 value，或经小额 Fund）且处于 DISPUTED；联邦 `POST /admin/verdict`（outcome=RELEASE，带多签签名集） | ① 回执 `chainExecuted:true`；② 合约 `escrowState` 读回 4（RELEASED）；③ 卖方地址收到出账 | ✅ **2026-10-02 核销**：全链 17 秒走完——`Fund`（1.1 TON 入金）→ `escrowState=1`；`Dispute` → `=3`；`Resolve(release)` → **`=4`（RELEASED）**；**每步轮询确认**（非盲等）。终态账本闭环：合约 0.8985 GRAM（部署值+各消息附着 value，1.1 入金 − 1.0 出账两清）、钱包回收 1 TON。新增 `FundMessageCodec`/`DisputeMessageCodec`（acton 向量对拍） |
| B13 | **TON Connect Fund 闭环（用户自己的钱包）** | 本地/隧道起服务配 HTTPS（`TGG_WEBAPP_URL`）→ Bot 深链打开 Mini App → 确认接受 → 连接钱包（Tonkeeper testnet）→ 点「支付入金」→ 钱包弹窗签名 | ① 钱包广播成功；② `escrowState` 读回 1（LOCKED）；③ 合约余额 ≈ 链上额 + gas 余量（账本恒等式可对）；④ 后续 DELIVER/CONFIRM/DISPUTE 同法可执行 | ☐ **未验（用户参与步骤）**——离线侧已就绪：控制器 7/7（payload 逐字节对拍/角色矩阵/形态反证）、页面 6/6、codec 向量对拍；真机需**用户在手机钱包确认**——未验前不得称「资金链已交到用户」 |

| B14 | **紧急暂停真机（ET-21）**（2026-10-03 新增） | testnet 部署（含 B10 流程，此次需带新 code 重部署）后：① 非联邦钱包发 Pause → 拒绝（exit 0x100b）；② 联邦发 Pause → 成功；③ 暂停中买方发 Fund → 拒绝（exit 0x1010）、状态不变；④ 联邦发 Unpause → 恢复（Fund 可再进）；⑤ 暂停中 RecordEvidence 与 ProposeUpgrade 成功；⑥ **经操作面触发**（`POST /admin/pause` 与 `/admin/pause/unpause` + Basic 凭据）确认链路闭环（而非仅手工构造消息） | 暂停冻结资金与状态推进；治理与存证不拦（暂停≠死锁）；unpause 后一切照旧 | ☐ **未验**——需 testnet 部署。（离线侧：acton **62 passed**，含 PAUSE-1..7 与 COMPAT-3 位布局 277→278 钉住） |

| B15 | **链上状态读面 + 对账回填（ET-19）**（2026-10-03 新增） | testnet 部署一笔订单后：① **读面**——沿命令流推进（lock/deliver/release），每步后经 `ChainGateway.escrowStateOf` 读回合约状态并核对（OPEN→LOCKED→…→RELEASED）；无写链栈时读面仍可用（只读不依赖私钥）；② **对账回填**——构造链上领先场景（用户经钱包直接上链、链下未跟）→ `POST /admin/reconcile` → `verdict=BACKFILLED`、链下状态按序追平；再对账 → `CONSISTENT`（幂等零动作）；③ **危险场景**——人为造"链下已 RELEASED 而链上仍 LOCKED"→ `verdict=DANGER` 且**零动作零保存** | 读面与合约 `get` 一致；回填每跳过域守卫、幂等；危险不一致只报告不动手 | ☐ **未验**——需 testnet。（离线侧：解析矩阵 4 + 判定矩阵 13 + 端点 3） |

## C. 依赖线上环境（部署/网络/构建工具链）

| # | 事项 | 怎么验 | 期望观察 | 状态 |
|---|---|---|---|---|
| C1 | **Admin API 鉴权与暴露面** | 部署后未授权访问 `/admin/words` | 被拒。**现状已变更**：应用层已于本轮加入 Spring Security 门禁（`SecurityConfig`：`/admin/**` 需 HTTP Basic；**未配置 `tgg.admin.*` 凭据时一律 401**），Nginx `deny all` 是并存的第二道门——真机复核见 C7 | ✅ 2026-09-24（Nginx `location ^~ /admin/ { deny all; }`；公网侧与服务器侧实测均 **403**）· ⚠️ **该次验证只覆盖了 Nginx 层**；应用层门禁已于 **2026-09-29** 真机复核通过（见 C7） |
| C2 | Vue3 前端构建 | `npm install && npm run build` | 构建通过（当前仅 README 规划） | ☑ **已核销（2026-10-03）**：`web/` 工程建成（vite 8.3 + vue 3.5.43 + @tonconnect/ui 3.0.2——`npm install` 实测版本，lock 入库）；**`npm run build` 通过**（31 模块 → dist：index 0.58kB / css 1.74kB / js 505.77kB）；`npm run preview` 服务与资源 200 已验。**渲染验证受限**：headless 预览因 `telegram.org` 运行时脚本在本地网络加载超时（真实 Telegram 客户端不受影响）——未截图 |
| C3 | Mini App 真机（SPEC S6 验收） | iPhone/Android 跑完整 TON Connect 流程 | 连接→签名→状态更新正常 | ☐ |
| C4 | TON Pay webhook（若启用） | 申请 Merchant API Key → 配 `TONPAY_API_SECRET` → 用真实支付回调（或 TON Pay 控制台重发）打 `/api/tonpay/webhook` | 验签通过；**校准两处口径**：① 签名原文拼接（现按 raw body HMAC——不符则改 `TonPayWebhookVerifier` 一处）；② payload 字段名（现按 `event/reference/amount/asset/txHash` 扁平形态）；金额/币种核对与防重生效 | ☐ **未验**——前置「Web 层」已消解（2026-10-03，服务端六步已落地）；仍缺 API Key 与真实回调。（离线侧：六步各分支 11 用例 + 登记 4 + 持久化 2） |
| C5 | MySQL 生产迁移 | 连生产库启动 | Flyway V1/V2 干净应用（本地 H2 已验；A3 旧库需 repair） | ✅ 2026-09-24（MySQL 8.0.43；v1/v2 均 `success=1`）· **V3 亦已应用**（见第二次部署） |
| C6 | TelegramBotHandler 群内角色查询升级 | 管理员命令在群里执行 | 当前固定 MEMBER（保守）；线上接 getChatMember 后放开 | ☐ 未验 |
| C7 | **应用层安全门禁（Spring Security）** | ① 不配 `TGG_ADMIN_USERNAME/PASSWORD` 时 `curl -i https://<站>/admin/words?guild=1`；② 配上凭据后同样打一次（不带凭据、再带 `-u user:pass`） | ① **401**（应用层拒绝——注意 Nginx 层若仍 deny all，公网看到的是 403，需**从服务器内部直连 8080** 才能绕过 Nginx 单独验应用层）；② 不带凭据 **401**、带对凭据可达 controller（读到词表或业务错误，均**不是** 401） | ✅ **已核销（2026-09-29）**——离线段策略已由 `AdminEndpointSecurityTest`（6 例）/ `AdminEndpointUnconfiguredSecurityTest`（1 例）在 `@WebMvcTest` 切片内离线覆盖（含"未配凭据带任意凭据仍 401"与"/api、/miniapp 不被误拦"）；**✅ 2026-09-29 真机核销**（第三次部署，服务器内部直连 8080 绕过 Nginx）：无凭据 `/admin/words?guild=1` → **401**；带 `-u $TGG_ADMIN_USERNAME:$TGG_ADMIN_PASSWORD`（取自 `/www/wwwroot/tgg/env.sh`，值未回显）→ **200**（词表 `[]`）；同一路径经 Nginx（`Host: www.7kyuedu.xyz`）→ **403**；`/api/trade/status` → **404**（未被安全层拦成 401）、`/miniapp/index.html` → **200**（放行面正确）。⚠ **端点变更注（2026-10-03）**：`/admin/words` 已随群管理移除（该路径现 404）——「带凭据 → 200」半条不可复现；「无凭据 401」结论仍成立（`/admin/**` 通配拦截，审计见 C7 同段） |
| C8 | **`/admin/chain/own-jetton-wallet` 端点 + Adnl provider 连通性** | 配 `TGG_CHAIN_JETTON_MASTER=<真实 jetton master>`，再 `curl -u user:pass 'http://127.0.0.1:8080/admin/chain/own-jetton-wallet?contract=<托管合约地址>'` | 返回 `{ok:true, ownJettonWallet:"…"}`，且该地址与链上一致；未配 master 时应 `503`（能力未启用）而非报错；节点不可达时 `502` 且**不含** `ownJettonWallet` 字段（绝不回假地址） | ✅ **已核销（2026-09-29）**——离线四分支已由 `AdminChainControllerTest` 离线覆盖；**✅ 2026-09-29 真机核销（核心未知量已解除）**：端点三分支实测——合法地址未配 master → **503**（`{"ok":false,"error":"链上能力未启用：未配置 tgg.chain.jetton-master"}`）、缺参 → **400**、无凭据 → **401**。**`AdnlLiteClient` 连通性已实测证实**：以独立探针（`java -cp <app.jar 内 105 个 lib>`，逐行复刻 `AdnlJettonWalletQuery` 的 `ownerArg → runMethod → addressFromResult`）在服务器上运行——mainnet 连上 lite-server `5.9.10.47:19949`、`get_wallet_address` 返回 `exitCode=0`（61 字节 boc）、解析出真实地址 `EQDmVdCZ2SALvyfCDVlPLr0hoPhUxgjNFtTAxemz9V_C5wbN`；testnet 连上 `49.12.147.32:27842`（对该主网地址返回 `exitCode=-256`，属预期）。**✅ 2026-09-29 A0-7 testnet 演练补齐最后一段**：应用实例配真实 `TGG_CHAIN_JETTON_MASTER`（自建 testnet jetton master `kQC87Ycbr28MRbK7PufCcsV_nHR6QUra1a_lsn05_UG9P_BV`，部署交易 `8337ef2b…d2c`）+ `TGG_CHAIN_TESTNET=true` 后 `curl -u <admin> …/own-jetton-wallet?contract=<合法地址>` → **200 + `{"ok":true,"ownJettonWallet":"EQC91OljuKhX9DOjhdD_LLDeXtew64y08NJy68yMiP_YUE2L"}`**（无凭据仍 401）。⚠️ ~~待跟进观察：返回地址前缀为 `EQ`…~~ **已修复（同日探针实测定根因）**：ton4j `toString(a,b,c)` 三参无 testnet 语义（第三参是 bounceable 位），testnet 形态只在 `toBounceableTestnet()`——`AdnlJettonWalletQuery` 已按 testnet 标志选序列化（testnet→`kQ`），对账用 `toRaw()` 解码级「同 hash 不同 tag」；修复后该端点在 testnet 配置下将返回 `kQ` 形态（待下次部署后真机复核） |
| C9 | **本轮新增配置项的线上注入** | 在 `/www/wwwroot/tgg/env.sh` 补齐并重启，然后逐项确认行为 | `TGG_LOG_SALT`（注入后用户 ID 掩码跨重启稳定；不注入则走进程级随机盐，启动日志有一条 warn）；`TGG_ADMIN_USERNAME/PASSWORD`（不注入 → `/admin/**` 全 401）；`TGG_CHAIN_JETTON_MASTER`（不注入 → 链上能力关闭、`/admin/chain` 返 503）；`TGG_GUARD_REPEAT_THRESHOLD`（不注入=0 → 重复检测不启用，行为与从前一致） | ✅ **已核销（2026-09-29）**——四项的**代码路径**都已有单测覆盖"未配置时的行为"，**✅ 2026-09-29 真机核销**：`/www/wwwroot/tgg/env.sh`（0600）含 `TGG_LOG_SALT` / `TGG_ADMIN_USERNAME` / `TGG_ADMIN_PASSWORD`（值遮蔽、未回显）；重启后启动日志 **无** `tgg.log.salt` 告警（`grep -c` = **0**）→ 固定盐生效；`TGG_ADMIN_*` 生效见 C7（401 → 200）；~~**未配** `TGG_CHAIN_JETTON_MASTER` → `/admin/chain` 返 **503**（见 C8）~~ → **2026-09-29 A0-7 演练已配置**（自建 testnet master，返回 200，见 C8）；**未配** `TGG_GUARD_REPEAT_THRESHOLD` → 取默认 0（重复检测不启用，行为与从前一致） |

| C10 | **`TGG_ALLOWED_CURRENCIES` 线上生效（"只收 USDT"）** | 在 `/www/wwwroot/tgg/env.sh` 写 `TGG_ALLOWED_CURRENCIES=USDT` → **重新部署** → 分别发 `/escrow create <卖方ID> 100 TON` 与 `... 100 USDT`（以及 `/escrow invite 100 TON`） | TON 一侧被拒并给出可读原因；USDT 一侧正常放行。**未配置该项时行为与从前完全一致**（TON + USDT 都收）——故"只收 USDT"必须显式配置才生效；写成枚举外的币种（如 `BTC`）应在**启动期**直接失败 | ⚠️ **部分核销（2026-09-29 第四次部署）**：✅ `env.sh` 已写入该键（0600 保持、已备份）、应用**以该环境启动成功**（`Started EscrowBotApplication in 32.922s`）+ 健康检查通过（`/miniapp` 200、`/api` 404、`/admin` 无凭据 401）。✅ **行为面已核销（2026-09-29 真机双向，第六次部署后）**：**TON 被拒**——`/escrow create 123456 100 TON` 在**预览阶段即拒**（回执逐字：「无法创建：交易币种：TON 不在本部署允许受理的范围内（当前允许：USDT）」，与 `AllowedCurrencies.requireAllowed` 文案逐字对齐）；**USDT 放行**——`create … 100 USDT` 正常出风险提示，15 秒内 `confirm` 命中登记**成功落单**（订单 #2 OPEN；日志 `03:39:32.831` ↔ 落库 `03:39:32.839`）。④ 日志同步留痕（`命令处理：/escrow …（actor=u:…）`，参数零泄漏）。遗留：沙箱读不到 `/proc/<pid>/environ`，"变量确实被进程读到"仍无**直接**观测（间接证据链见前） |

| C11 | **`TGG_GUARD_ALLOWED_CHANNELS` 线上生效（GM-12 频道傀儡防御）** | ① **不配**该变量 → 群里以频道身份发言应**照常放行**（默认行为不变）；② 配 `TGG_GUARD_ALLOWED_CHANNELS=<频道名>` → 该频道帖放行、其它频道帖**被删**；③ 配成**空串**（`TGG_GUARD_ALLOWED_CHANNELS=`）→ **所有**频道帖被删 | ⚠️ **部分核销（2026-09-29）**：代码已上线；**未配置**该键（线上三个新键的键名复查：本项 = 0）→ 应用以"不启用"状态正常启动，即 ① 侧的**部署前提**成立；**①–③ 的行为面仍需真机群验证**。开启只需改 `env.sh` 再重启 |

| C12 | **`TGG_RISK_*` 线上生效（GM-24/25 入群风控）** | 在 `/www/wwwroot/tgg/env.sh` 写 `TGG_RISK_WEIGHTS=NO_USERNAME=30`（必要时加 `TGG_RISK_REVIEW_THRESHOLD` / `TGG_RISK_REJECT_THRESHOLD` / `TGG_RISK_NEW_ACCOUNT_DAYS` / `TGG_RISK_ALLOWED_LANGUAGES`），重启后观察新成员入群 | ① **不配** `TGG_RISK_WEIGHTS` → 该防御不启用，行为与从前一致；② 配了 → 未设 @username 的成员按阈值走 REVIEW/REJECT；③ 权重表写成**未知因子名**或非法分值 → **启动期直接失败**（不让配置错误静默失效成"以为有防护"） | ✅ **REJECT 行为面已核销（2026-09-29 真机，随 A15 测试窗口顺带）**：配置生效后真实踢人 + 留痕逐字对上（`分数=30 命中={LANGUAGE_NOT_ALLOWED=30}`）；「未配置 = 不启用」侧此前已核。**未真机单测**：REVIEW/ALLOW 两档行为面（离线已穷举）；非法配置的启动期失败面未线上验（本地有测） |

## D. 待用户拍板后才能定案（非验证项，阻塞对应开发）

B1 费率 / B2 首单形态 / B3 信用分 5 点澄清 / B4 KYC 口径 / C1 多签语义 / D TON 确认数语义 /
M Agentic Wallets / 排名范围（群内/跨群）——见 `docs/UNDECIDABLE-DECISIONS.md`。

---

## 部署实录（2026-09-24 · 首部署）

**环境**：Ubuntu 26.04.1 LTS（**受限容器**：root 无 `CAP_DAC_OVERRIDE`/`CAP_FOWNER`，禁 setuid/setgid）·
2 vCPU / 3.3 GiB / 75 GB 盘 · 宝塔面板。

**⚠️ 与常规部署的关键差异（下次部署先读这段）**：

1. **apt 装不了需要下载的包**：apt 下载时要降权到 `_apt`，而容器禁 `setgroups/setegid/seteuid`
   → `Permission denied`。所有"能装上"的 apt 包其实都是本就已装的。
2. **宝塔安装器会退化成源码编译**：因老包名（`libpng12-dev`/`zlibc` 等）在新系统 `Unable to locate`，
   它会 gcc 逐个编译依赖（OpenSSL → curl → …），2 核机上代价极高。
3. **实际采用的绕行**：**手动装二进制包**（已验证可行）
   - JDK：`mirrors.huaweicloud.com/openjdk/21.0.2/openjdk-21.0.2_linux-x64_bin.tar.gz` → `/opt/jdk-21.0.2`
   - MySQL：`dev.mysql.com/get/Downloads/MySQL-8.0/mysql-8.0.43-linux-glibc2.28-x86_64.tar.xz` → `/opt/mysql-8.0.43-…`
   - tar 需加 `--no-same-owner`（沙箱不能 chown，否则报错刷屏但文件仍正确）
4. **t64 ABI 坑**：Ubuntu 24.04+ 把 `libaio.so.1` 改名为 `libaio.so.1t64`，MySQL 二进制仍找旧名
   → 补软链 `ln -sf libaio.so.1t64.0.2 /usr/lib/x86_64-linux-gnu/libaio.so.1`。
5. **沙箱读不到面板私有目录**：上传的 jar 落在 `/www/server/panel/data/agent/mcp_uploads/…`，
   MCP 的 Bash 沙箱无权读。**绕法：用宝塔「计划任务」（面板权限）把文件复制到 `/www/wwwroot/tgg/`**。
6. **宝塔 Java 项目拼的 `project_cmd` 有坑**：`-jar` 排在 `-Xmx` 之前（JVM 参数顺序错），
   且不支持 `env_list` → 需用 `JavaProjectModify` 重写 `project_cmd`，把环境变量内联进命令；
   改 `project_cmd` 时**必须同时改 `project_jar`**（否则报"项目jar包名称不在项目启动命令中"）。
7. **宝塔 Java 项目的启停脚本拉不起进程**（两次实测：`restart`/`start` 返回"操作已执行"，
   但无 java 进程、无日志）→ 实际启动改用 `setsid nohup java -jar …`。
   代价：应用**无守护/无自启**，需后续用 systemd 或修好宝塔链路。

**运行形态**：
- 应用：`/www/wwwroot/tgg/app.jar`（宝塔项目 `tgg-bot` 的 `project_cmd` 指向它），env 内联于命令
- MySQL：`/opt/mysql-8.0.43-…`，data `/opt/mysql/data`，监听 `127.0.0.1:3306`，库 `tgg_escrow`
- Nginx：反代 `www.7kyuedu.xyz:80` → `127.0.0.1:8080`，`/admin/` 白名单 deny all

**核销结果**：V1 ✅（Flyway v2 + Tomcat 8080 + Bot 长轮询）· V2 ✅（私聊命令得回执）·
V3 ✅（`escrow_orders` id=1，buyer 8724975623，100 USDT，state=OPEN）· V4 ✅（域名→应用）·
V5 ✅（`/admin/words` 403）。

---

## 第二次部署实录（2026-09-24 · T2 查询 + cancel + 乐观锁上线）

**上线内容**：`5d24bf9`（T2 状态查询）、`55c4ad1`（`/escrow cancel`）、`ba06493`（乐观锁 + V3 迁移）。

**关键绕行：全量 jar 上传失败 → 改走增量补丁**
- 67M fat jar **两次上传均被本地网络掐断**（`curl (56) Connection reset by peer` /
  `Can't assign requested address`；跨境上行约 33 KB/s）。
- **做法**：`git diff --name-only fbb6ce0..HEAD -- '*.java' '*.sql'` 列出变更面（8 个产品类 + 1 迁移脚本），
  从 `target/classes` 打包成 **13 KB** 补丁 → 上传（1.8 秒，`sha256` 端到端校验一致）→
  服务器侧 `jar uf app.jar -C <dir> BOOT-INF` 更新 fat jar → `jar tf` 核实新条目在包内。
- **前提**：本次无新增依赖（`telegrambots-client` 首次部署已在包内）；**若下次有依赖变更，此法不适用，必须全量**。

**迁移与并发**：
- Flyway **V3 已在生产库应用**：`Migrating to "3 - order optimistic lock"` → `now at version v3`；
  `escrow_orders.version` 列为 `bigint NOT NULL DEFAULT 0`，存量行（id=1）取值 0。
- **乐观锁实测生效**：cancel 后 `version` 由 0 递增为 1 —— 即 `@Version` 在真实 MySQL 上工作。

**回滚手段**：旧 jar 备份在 `/www/wwwroot/tgg/app.jar.bak-deploy`（70,010,716 字节）。

**遗留**：① 应用无守护/自启（见首部署第 7 条）；② 临时计划任务 `tgg-copy-patch` 与 `/www/wwwroot/tgg/BOOT-INF/` 解包残留待清理；
③ 宝塔源码编译进程仍在空转。

---

## 第三次部署实录（2026-09-29 · 安全门禁 + ChainGateway/Adnl + 重复检测上线）

**上线内容**：`42e6ed4`（Spring Security 应用层门禁）、`f74247c`…`6e7edb6`（链上 provider 换 Adnl + `ChainGateway` + `/admin/chain`）、`b390e39`/`d0847a7`（重复消息检测装配）、`b818c49`…`49ea046`（文档对账）——即当时 `main` 的 HEAD `49ea046`。

**为什么走增量补丁而非全量上传**：83 MB fat jar 跨境上行实测约 26 KB/s，一次尝试在 3.8%（≈3.1 MB）即被掐断，与第二次部署的结论一致。改为增量：本地产出 **308,840 字节** 补丁（41 个 `BOOT-INF/classes` 文件 + 5 个 `tgg-*.jar` + `classpath.idx` / `layers.idx`）。

**搬运通道（受限容器下的关键绕法）**：
1. 面板 MCP 的 `Upload` 把文件落到 `/www/server/panel/data/agent/mcp_uploads/<key>/…`，该目录 `0700`，而 MCP 的 `Bash` 沙箱（bwrap，uid 0 但无真实 `CAP_DAC_OVERRIDE`）**读不到**它（HANDOFF 坑 5 仍成立）；
2. 用**面板文件工具**（以真实 root 运行）写入一个桥脚本，再用**面板计划任务**（真实 root 执行）运行它，把白名单文件拷到 `/www/wwwroot/tgg/`；用完即删该任务；
3. 6 个新增 lib（`adnl-2.1.0`、`tl-2.1.0`、`spring-security-core/config/crypto/web-6.5.11`）**直接在服务器从 Maven Central 下载**（实测 ~800 KB/s），逐个 `sha256` 与本地 fat jar 内同名条目比对**完全一致**——避免跨境传大文件。

**应用方式与自校验**：桥脚本先按「新 jar 的完整条目清单」删除线上 jar 中已不存在的 `BOOT-INF/classes|lib` 条目，再 `jar uf0 app.jar -C <patch> BOOT-INF`（**必须 `0`/STORED**，否则嵌套 jar 被压缩，启动 `ClassNotFoundException`）。事后把补丁后 jar 的条目清单与新 jar 清单**逐条比对**：252 个文件条目**缺失 0、多出 0**；唯一差异是 `META-INF/maven/com.tg.escrow/tgg-app/pom.xml`（构建元数据，运行时不被读取）。

**两个会致命的坑（本次实测）**：
1. **遗留计划任务 `tgg-stage-seven`（每分钟）会反复改写 `app.jar`**——它把 `seven.zip` 里的 99 个旧 lib 用 `jar uf0` 叠回并 `mv` 覆盖。不先停掉它，增量补丁会在 1 分钟内被旧 jar 覆盖，形成「新 classes + 旧模块 jar」的必崩组合。已删除该任务（属上一轮遗留，与第二次部署「遗留②」同类）。
2. **`jar uf0` 只增不删**：线上 `BOOT-INF/classes/db/migration/V3__order_optimistic_lock.sql` 与新 jar 的 `tgg-escrow.jar!/db/migration/V3…` 构成**同一版本号两份迁移**，不删除会让 Flyway 启动时报 version 3 重复。故补丁必须带「删除新 jar 已不存在的条目」这一步。

**重启**：按 `keepalive.sh` 的路径（source `env.sh`）拉起——**不要**用面板 Java 项目的 restart（其 `project_cmd` 内联的是旧变量、不 source `env.sh`，新配置不会生效）。实测启动日志：`Successfully validated 5 migrations`（V1–V3 校验通过）→ `Migrating to version "4 - trade invites"` → `"5 - trade reviews"` → `now at version v5`；`Global AuthenticationManager configured with UserDetailsService bean`；`Started EscrowBotApplication in 41.164 seconds`。

**核销**：C7 ✅（401 / 200 / 403 / 404 / 200 五路实测）· C8 ✅（400 / 401 / 503 三分支 + `AdnlLiteClient` 连通性经**独立探针**证实，mainnet 与 testnet 均可连）· C9 ✅（盐、凭据、链上关闭、重复检测默认）。详见对应行。

**回滚手段**：`/www/wwwroot/tgg/app.jar.bak-deploy-20260928-171101`（78,664,772 字节，部署前原 jar）。回滚 = 覆盖回该文件 + 按 `keepalive.sh` 重启。

**遗留（2026-09-29 后续会话更新）**：① ~~线上仍存上一轮的解包残留~~ **已清理**——`BOOT-INF/`、`stage/`、`stage2/`、`stage2b/`、`tonprobe/`、`seven-ready`、`gson-ready`、`patch.tgz`、`*.flag`（约 87 MB）；`app.jar`、5 个 `app.jar.bak-*`（回滚网）、`env.sh`、`keepalive.sh`、两个日志、`dbbackup/` **均保留**。② `TGG_CHAIN_JETTON_MASTER` 未配（属部署者决策），故 `/admin/chain` 仍返 503——连通性已证，配即生效。③ ~~Bot Token 与 DB 密码仍明文存放于面板 `project_cmd`~~ **已硬化**：`project_cmd` 改为 `cd /www/wwwroot/tgg && . /www/wwwroot/tgg/env.sh && /opt/jdk-21.0.2/bin/java -Xmx768m -Xms256m -jar /www/wwwroot/tgg/app.jar --server.port=8080`，凭据只存在于 0600 的 `env.sh`。**顺带修掉一个隐性陷阱**：旧命令内联变量、**不 source `env.sh`**，用面板重启会丢掉 `TGG_ADMIN_*`/`TGG_LOG_SALT`；新命令不会。已逐个成分校验（source 成功、`java` 可执行、`app.jar` 在、`TGG_LOG_SALT`/`TGG_ADMIN_USERNAME`/`TELEGRAM_BOT_TOKEN` 均非空），**未真跑**（会与线上实例抢 8080 与 Bot 轮询）。仍待办：凭据轮换（轮换 Bot Token 会打断长轮询，需专门安排时段）。

---

## 第四次部署实录（2026-09-29 · 三波接线一次上线）

**上线内容**：本地 HEAD `f089ac0` 的构建产物，覆盖三波改动——① 信用/榜单读模型（`b3ac31a`/`f019d07`+`859aab9`/`7a670a3`：`/escrow rank`、`/escrow credit`）；② 频道傀儡防御 GM-12（`4fb4f8a`+`89701cf`）；③ 入群风险评分 GM-24/25（`f089ac0`）。另含更早的 `BannedWordMatcher` 换 Trie 与 `AllowedCurrencies`（此前一直未上线）。

**部署前实测的线上状态**：`app.jar` = 83,247,428 字节 / 2026-09-28 17:11；进程 PID 1541001（`keepalive.sh` 拉起）；`env.sh` 0600、含 `TGG_LOG_SALT`/`TGG_ADMIN_USERNAME`/`TELEGRAM_BOT_TOKEN`/`TGG_DB_NAME`（**只核键名，未读值**）；三个待加键**均不存在**；磁盘 55 G 可用。

**补丁**：本地 `mvn -DskipTests package` → 抽 **332,314 字节** 补丁（46 条目 = 41 个 `BOOT-INF/classes` 文件 + 5 个 `tgg-*.jar`；**无新增依赖**，故不需在服务器下载任何 lib），`sha256 = 04da4206…`，端到端校验一致。

**搬运通道**：与前次相同（面板 `Upload` 落在 0700 私有目录 → **沙箱读不到**（本轮复现 `Permission denied`）→ 面板文件工具写桥脚本 + 面板计划任务以真实 root 执行）。**本次新增一个坑**：macOS 打的 tar 保留 `uid 501` 属主，服务器沙箱无 `CAP_CHOWN`，`tar xzf` 会因 `Cannot change ownership` 失败（exit 2）——**必须加 `--no-same-owner`**（文件仍会写出，但 `set -e` 会中断后续步骤）。桥任务用完即删（已复核：计划任务只剩 `tgg-keepalive` 与证书续签）。

**条目对齐（坑 2 的处理）**：用 **Python 读 zip 条目**做集合比较——**不要用 `comm`**（本轮它在 locale 下报 "not in sorted order"，输出完全是错的，差点误判"要删 30 个条目"）。实测：线上 41 个 classes 文件 = 补丁 41 个，**需删 0、新增 0** → 无需删除步骤。迁移脚本在 `tgg-escrow.jar` 内（不在 classes），随补丁一并更新。

**应用方式**：`jar uf0 app.jar -C /tmp/tgg-patch .`（`jar` 用 `/opt/jdk-21.0.2/bin/jar`；`0` 不可省）。结果：`app.jar` 83,247,428 → **83,280,873 字节**；抽查 `TradeCommandHandler.class`/`MemberJoinHandler.class`/`TelegramBotHandler.class` 时间戳已更新，`tgg-core`=97,038 / `tgg-escrow`=164,456 与补丁一致。

**配置变更**：`env.sh` 追加 `export TGG_ALLOWED_CURRENCIES=USDT`（追加前备份 `env.sh.bak-20260928-191919`；权限仍 0600）。**另外两项刻意不配**：`TGG_GUARD_ALLOWED_CHANNELS`（频道傀儡防御）与 `TGG_RISK_WEIGHTS`（入群风控）**未配置 = 不启用**，行为与从前一致——要开启需显式给值。

**重启方式（重要）**：**不要用面板 Java 项目的 restart** —— 本轮实测面板 pid 文件内容（`159673`）与真实 java 进程（`1541001`）**不一致**，restart 可能杀错或杀不掉。正确路径 = `kill <真实 PID>`（SIGTERM）→ 由 **`tgg-keepalive` 计划任务**（每分钟，`. env.sh` + `setsid nohup java …`）自动拉起。实测：`19:19` 杀 → `19:20:01 app DOWN -> starting` → 新 PID 1544412。

**启动与健康核销（服务器内部直连 8080）**：
- Flyway：`Schema tgg_escrow is up to date. No migration necessary.`（本次无迁移）
- `Tomcat started on port 8080` → **`Started EscrowBotApplication in 32.922 seconds`**；本进程 `ERROR=0`
- `/miniapp/index.html` → **200**；`/api/trade/status` → **404**（未被安全层误拦）；`/admin/words?guild=1`（无凭据）→ **401**（fail-closed 生效）

### 真机核销回执（2026-09-29 · 用户在真实群实测）

以下为用户在真实群里发出的命令与**实际收到的回执**（原文），逐条对断言：

**① `/escrow rank` → ✅ A12 通过**
```
📊 本期交易排行
🥇 2***2  🌱
   评分 25
🥈 8***3  🌱
   评分 25
```
断言逐条核对：标题 ✅；等级徽标 🌱 ✅；**脱敏形态 `2***2`（首字符 + `***` + 末字符）** ✅；**不含任何明文用户 ID** ✅；
同分（25 = 25）时按 user ID 升序（`2***2` 在 `8***3` 前）✅ —— 与 `TraderCreditService.rank` 的「先排后截 + 同分定序」一致。

**② `/escrow credit` → ✅ A13 通过（主路径）**
```
📊 你的信用摘要
🌱 新手 · 评分 25/100
完成交易 0 笔 · 收到评价 0 条（暂无评价）
维度拆解：
· 完成笔数：0/50
· 争议率：0%（反向：越低越好）
· 好评率：0%
· 对手集中度：100%（反向：越低越好）
· 活跃时长：1/365 天
```
**分数构成手算复核**：完成 0/50=0（×0.30）+ 争议反向 (1−0)=1（×0.25）+ 好评 0（×0.20）+ 多样性反向 (1−1)=0（×0.15）+ 活跃 1/365（×0.10）
= 0.2503 → **25** —— 与回执一致 ✅。数据自洽：该用户是库里唯一那笔订单（id=1，CANCELLED）的当事人 → 0 完成、1 对手 100%、活跃 1 天。

**③ `/escrow credit 12345`（他人 ID）→ ✅ A13 通过（拒绝路径）**
```
信用摘要只能查自己（/escrow credit 不接受他人 ID）——榜单默认匿名，这里也不提供按 ID 反查他人信用的入口。
```
与 `CREDIT_SELF_ONLY` 完全一致，**未泄漏任何他人数据** ✅。

**④ `/escrow create 123456 100 TON` → ⚠️ 未构成 C10 核销（属预期行为）**
```
⚠️ 交易前风险提示：你将支付 100，请核对对方身份后确认发起。
确认无误请发：/escrow confirm 123456 100 TON
```
**这不是配置失效**：`TradeCommandHandler` 的 `create` 分支只做 `pending.prepare(...)` + 风险提示、**不调 `service.initiate`**，
而币种校验（`AllowedCurrencies.requireAllowed`）在 `EscrowTradeService.initiate` —— 即 **`confirm` 那一跳**。
→ **C10 的行为面仍需发一次 `/escrow confirm 123456 100 TON`**（期望：被拒并给出可读原因）。

**⑤ 由 ② 的实测暴露并修复的一处文案缺陷**：原实现在 `completed == 0 && reviewCount == 0` 时**无条件**写
「当前的 N 分**全部来自「无争议」「无对手集中」两个反向维度的满分**」——而这用户对手集中度是 **100%**（该维度**0 分**），
25 分里只有「无争议」一项满分。**条件写宽导致对一部分用户说出与数据不符的话**。
已修（措辞不再数维度个数）并**新增一条用例钉住该边界**（`CreditSummaryViewTest.partialHistoryDoesNotClaimBothReverseDimensions`，用同一组线上数据算得 25 分）。

**⑤ 第六次部署后 · `/escrow create 123456 100 TON`（2026-09-29 11:34:48 真机）→ ✅ ③④ 双核销**
```
/escrow create 123456 100 TON
无法创建：交易币种：TON 不在本部署允许受理的范围内（当前允许：USDT）
```
- **③**：拒绝发生在**预览阶段**（旧版此处回风险提示、把用户引向注定失败的 confirm）——`createPreviewsRejectsCurrencyOutsideAllowedSet` 的真机形态逐字对上；
- **④** 服务器日志同步（`app.log`）：`2026-09-29T03:34:47.362Z … TradeCommandHandler : 命令处理：/escrow create（actor=u:8106518c06c4）`——子命令在、actor 脱敏（非明文 ID）、**参数（123456/100/TON）零泄漏**，时间戳与回执对齐（03:34:47Z ≈ 11:34:48 +08）。

**⑥ A14 + C10 对照/confirm（2026-09-29 11:39 真机）→ ✅ A14 完成、C10 行为面双向完成**
```
频道身份：1 / 测试消息 / /escrow rank / 111 / /escrow rank / 1111
```
- **A14**：4 条频道普通消息均未被删（白名单未配置 = 不启用 = 放行 ✓）；2 条频道帖 `/escrow rank` **零回执**，且 `app.log` **零命令记录**——`BotDispatcher` 未把频道帖路由给业务命令（双侧证实 ✓）；
- **C10 对照组**：`create … 100 USDT`（03:39:17 日志）正常出风险提示 → 15 秒后 `confirm … USDT`（03:39:32 日志）命中登记**成功落单**——`escrow_orders` 订单 **#2 OPEN**（落库 03:39:32.839 与日志 8ms 对齐）。「只收 USDT」的**放行面**就此证实。
- **方法论更正（记录以免复用错清单）**：本轮清单曾把「confirm 期望被拒」错标在 USDT 对照组——USDT 的 create→confirm 是**成功路径**；「confirm 被拒」是 TON 那条（币种校验）的期望。实际行为正确，是清单错了。

**⑦ A15 入群 REJECT 档（2026-09-29 11:54 真机）→ ✅ GM-24/25 风控踢人行为核销完成**
```
⚠️ 新成员未通过入群安全校验，已被移出。
```
- 测试窗口配置：`TGG_RISK_WEIGHTS=NO_USERNAME=30,LANGUAGE_NOT_ALLOWED=30` + `TGG_RISK_REJECT_THRESHOLD=30` + `TGG_RISK_ALLOWED_LANGUAGES=zz`（主方案语言因子直接命中，`NO_USERNAME` 兜底未用上）；
- 服务器留痕（`app.log` 03:54:46Z）：`入群风控：拒绝入群（chat=u:09ee14e6dfac user=u:a76ce16ff1cb 分数=30 命中={LANGUAGE_NOT_ALLOWED=30}）`——分数/命中因子逐字可复算（30 ≥ REJECT 30，判定先于 REVIEW 档），ID 全程脱敏；
- 回执文案与 `MemberJoinHandler` REJECT 成功分支逐字一致；移出动作真实执行（小号已不在群）；
- **窗口收尾**：`env.sh` 已从 `env.sh.bak-a15-test` 恢复（三测试键清零）+ 重启，误伤窗口关闭。
- 方法备注：小号**有** username 时 `NO_USERNAME` 不命中——故用「允许列表设为不可能语言 `zz`」驱动 `LANGUAGE_NOT_ALLOWED`（语言为空的小号不计入，有 `NO_USERNAME` 兜底）。

**仍未核销**：A15 的 REVIEW/ALLOW 两档真机面（离线已穷举）、C11 的配置后行为面（频道白名单配了之后的放行/拦截）、C12 的非法配置启动失败线上面。

**回滚手段**：`app.jar.bak-deploy-20260928-191843`（83,247,428 字节 = 部署前原 jar）+ `env.sh.bak-20260928-191919`。回滚 = 覆盖回该 jar、删掉新增的 env 行、kill 进程等 keepalive 拉起。

---

## 第五次部署实录（2026-09-29 · `/escrow credit` 文案修复上线）

**上线内容**：`aa51e21`（含 `5e04e25`）——`CreditSummaryView` 里「尚无成交记录」那句解释文案的两轮收紧（原始措辞 → 上一版 → 现版）。**生产代码只动了 `tgg-escrow` 一个类**。

**最小补丁**：只带 **`BOOT-INF/lib/tgg-escrow-0.1.0-SNAPSHOT.jar`**（148,487 字节压缩包，`sha256 = f549b93d…`）——因为 `tgg-app` 的类未变（它只是调用方）。比第四次的 332 KB 更小，搬运与替换都快。

**两处方法论纠正（本轮实测撞到，值得记下）**：
1. **`strings` 不能用来验证 Java class 里的中文串**——它默认只输出 ASCII 可打印序列，中文常量根本不会出现，于是"旧措辞 0 命中"是**假通过**。正确做法是 `unzip -p <jar> <class> | grep -ac "<中文>"`（`-a` 把二进制当文本；Java class 的字符串常量对 BMP 字符就是标准 UTF-8 字节）。
2. **对照实验的关键词必须与被对照的那一版匹配**——我一度拿**新版**措辞去 grep 线上**旧版** jar，得到 0 命中，那**不构成"线上需要更新"的证据**（必然为 0）。换成原始措辞后：线上 `1` 命中、补丁 `0` 命中，才是有效对照。

**替换后验证（在线上 jar 内做，不是只看补丁）**：新措辞（`反向维度」在数据为零时`）**1** 命中、旧措辞（`两个反向维度的满分`）**0** 命中；`app.jar` 83,280,873 → **83,280,900**（+27 字节 = 文案差值）。

**重启**：`kill 1544412` → `keepalive` 于 `02:12:01 app DOWN -> starting` 拉起 → 新 PID **1558659**；启动日志 `Schema tgg_escrow is up to date` → `Tomcat started on 8080` → **`Started EscrowBotApplication in 31.714 seconds`**（本进程无 ERROR）。

**健康核销**：`/miniapp/index.html` **200**、`/api/trade/status` **404**、`/admin/words?guild=1` 无凭据 **401**。

**回滚手段**：`app.jar.bak-deploy-20260929-021103`（83,280,873 字节 = 本次部署前的 jar）。回滚 = 覆盖回该文件 → `kill <真实 PID>` → 等 keepalive 拉起。

**仍待真机核销**：A14（频道帖）、A15（入群三档）、**C10**（需在 10 分钟内**连发** `create` + `confirm`，因为 `PendingTradeRegistry` TTL = `PT10M`；`create` 单独一跳只预览、不校验币种）、C11 / C12（两项防御线上未启用）。

**遗留 / 未核销**：
① **A12–A15 未核销**（真机行为：`/escrow rank`、`/escrow credit`、频道帖、入群三档）——需真实群与客户端。
② **C10 仅部分核销**：`env.sh` 已含该键、应用以该环境正常启动，但**"TON 被拒 / USDT 放行"这一行为面未实测**（需真机命令）；另注意沙箱读不到 `/proc/<pid>/environ`，故"该变量确实被进程读到"未能**直接**观测，只有间接证据（启动成功 + keepalive 的 `. env.sh` 路径）。
③ C11 / C12 同样只核销"**未配置=不启用**"这一侧；要核销"配了之后的行为"需另行配置 + 真机。
④ 若日后要开启这两项防御，**只需改 `env.sh` 再重启**（无需重新部署 jar）。


## 第六次部署实录（2026-09-29 · 开发侧功能收口上线：③④②）

**上线内容**（`ce83ab0`）：③ create 预览阶段提前 `requireAllowed`（不再引导用户发注定失败的 confirm）；
④ `TradeCommandHandler` 命令级 INFO 日志（子命令名 + 脱敏 actor，参数不落日志）；
② REVIEW 档管理员通知通道（`AdminReviewNotifier` 窄端口 + `tgg.risk.review-notify-chat` 三态配置，未配 = 不启用）。

**增量补丁**：`tgg-patch-6th.zip` **167,426 字节**（sha256 `908e72e5…`），5 条目——
`BOOT-INF/classes` 下 4 个类（TradeCommandHandler / BotWiring / MemberJoinHandler 替换 + BotReplyAdminReviewNotifier 新增）
+ `BOOT-INF/lib/tgg-core-0.1.0-SNAPSHOT.jar` 整体替换（内含新的 `AdminReviewNotifier`）。
`app.jar` 83,280,900 → **83,286,232**（+5,332）；`jar uf0`（`0` 不能省）重建后 287 条目（286 + 1 新类），
**duplicate entries: []**（tgg-core 单份，无双份类加载风险）。注意：线上 287 vs 干净构建 284，差 3 条为
历史叠加残留（第四/五次部署前即存在、服务健康），**本次未清理**——删除条目属独立高危动作，待另行确认。

**搬运（坑 4 复现 + 既定绕法）**：上传目录 `0700` Permission denied → 面板文件工具写桥脚本
`bridge-move-6th.sh` + 计划任务（id=18，每 10 秒）以真实 root 搬到 `/www/wwwroot/tgg/`，落地即删任务与脚本。

**jar 内字节级验证（`unzip -p | grep -a`，坑 7/8）**：
- ③ `TradeCommandHandler.class` 含 `requireAllowed` 调用痕迹 ✓；
- ④ 同类含 `actor=` 日志文案 ✓；
- ② `BotWiring.class` 含 `tgg.risk.review-notify-chat` 装配键 ✓、`tgg-core` jar 内 `AdminReviewNotifier.class` 含「入群风控待复核」✓。
- 对照实验纠偏记录：拿「不在本部署允许受理的范围内」grep `TradeCommandHandler.class` 得 0——**预期**，
  该文案在 `AllowedCurrencies`（tgg-escrow jar）而不在调用方；0 命中不构成缺件证据（坑 8 又一次应验）。

**重启**（坑 9/10）：`kill 1558659` → `tgg-keepalive` 于 03:27:00 自动拉起 → 新 PID **1564344**。

**健康核销**：Flyway `Schema tgg_escrow is up to date`（03:27:19）、`Started EscrowBotApplication in 30.649s`
（03:27:36，与 ps 的 PID 一致）、`/miniapp/index.html` **200**、`/admin/words?guild=1` 无凭据 **401**、
`/api/trade/status` **404**。回滚网：`app.jar.bak-deploy-20260929-032501`（第五次版）。

**本次新增的可核销面**：
- **C10 行为面**（真机）：`/escrow create <ID> 100 TON` 现应在**预览阶段**即拒（「无法创建：交易币种：TON 不在本部署允许受理的范围内…」）——③ 上线后这是 C10 的主要观察点；
- **④ 命令日志**（真机后看服务器日志）：每条 `/escrow …` 应有 `命令处理：/escrow <子命令>（actor=<脱敏ID>）`，且无参数明文；
- **② 复核通知**：未配置 `TGG_RISK_REVIEW_NOTIFY_CHAT` = 不启用（与从前一致）；配了才投递。

**仍未核销**：A14（频道帖）、A15（入群三档，需先给踢人口径）、C10 的 confirm 一跳——均需真实群客户端。


---

## 第七次核销实录（2026-10-01 · 本地实例 + 新 bot @nq9831ops_bot）

> 与前几次不同：本轮**不部署线上**（用户明确「不动线上旧部署」），改用**本地 tgg-app 实例** + 新 bot token 做真机核销。
> ⚠️ 教训：本地实例与线上若用**同一 token** 会互抢长轮询（实测 409 `Conflict`）——本轮先遇到 409，确认线上关闭后才独占。

**环境**：`mvn -pl tgg-app spring-boot:run`，`SERVER_PORT=8090`（8080 被本机 nginx 占用），
`TGG_DB_NAME=tgg_escrow_local`（全新库，Flyway 迁到 v6），`TGG_LOG_SALT=local-dev-salt-0123456789`（固定盐便于跨重启关联），
`TELEGRAM_BOT_TOKEN=***`（**只注入进程环境，不落盘、不进日志**），为核销 ET-35 临时加 `TGG_FEE_PLATFORM_RATE=0.02`。
启动证据：`deleteWebhook: true` → `Started EscrowBotApplication`，全程无 409。
bot 身份（`getMe`）：`Telegram-Federal-Smart-Governance-Bot` / `@nq9831ops_bot` / id `8800156074`。

**核销清单（本轮全部实测）**：

| 项 | 结果 | 观察（回执逐字 / DB / 日志） |
|---|---|---|
| A1 命令回执 | ✅ | `/escrow` → 完整 12 条用法列表 |
| A8 status 不存在 | ✅ | `/escrow status 1` → 「订单 #1 不存在（请核对订单号）」 |
| A12 空榜 | ✅ | `/escrow rank` → 「📊 本期榜单：暂无数据」（空库如实回，非空白） |
| A12 非法条数 | ✅ | `/escrow rank abc` → 「榜单条数必须是数字，例如 /escrow rank 10。」 |
| A12 超范围 | ✅ | `/escrow rank 99` → 「榜单条数必须在 1–20 之间。」 |
| A13 空数据形态 | ✅ | `/escrow credit` → 「🌱 新手 · 评分 40/100」+ 五维拆解 + 「⚠️ 尚无成交记录…反向维度在数据为零时按满分计，故当前的 40 分不代表实际信用」 |
| A9 对手方通知 | ✅ | 双账号：甲 `lock` → **乙私聊收**「订单 #1：资金托管已登记，请交付（/escrow deliver 1）」；乙 `deliver` → **甲私聊收**「订单 #1：对方已标记交付，请在维护期内验收」。日志两个不同脱敏 actor（`u:f9990a84f348` 甲 / `u:94b6a17bf0b7` 乙）。**送达时未误报**「对方可能收不到通知」 |
| A16 交易群绑定+置顶 | ✅ | 群内 `confirm` → 群公告「📢 本群已绑定为订单 #3 的交易群，全程留痕。如有争议请发起 /escrow dispute 3」+ 回执「已创建订单 #3 / 交易群：已绑定本群，规则公告已置顶」。**私聊不噪声**：订单 #1/#2 私聊回执无任何群提示 |
| A17 静默期 | ✅ | `release` 回执含「交易群：已进入静默期（期满自动归档）」；DB `trade_groups` = `state=SILENT, silence_started_at=2026-09-30 18:56:27(UTC)` |
| A17 惰性归档 | ✅ | 静默期满后群内一条普通消息 → 群公告「📢 交易 #3 的静默期已满，本群归档。bot 将退出本群，如需继续请新建交易。」+ bot 退群 |
| A18 unban/unmute | ✅ | 四条逐字：「已封禁 8709189795」「已解封 8709189795」「已禁言 8709189795（10 分钟）」「已解除禁言 8709189795」；行为层：解除后该用户在群内发普通消息**正常显示**（未被拦截） |

**顺带核销 ET-35（首单豁免，本会话交付物 `c116cd2`）**——临时配 `TGG_FEE_PLATFORM_RATE=0.02` 后两笔实测：
订单 #1（卖方首笔）回执「**首单免平台费（应收 2.000000 USDT），卖方实收 100.00000000 USDT**」；
订单 #2（卖方已有 1 笔）回执「**平台费 2.000000 USDT，卖方实收 98.00000000 USDT**」。两分支与代码逻辑逐字一致。
（`100.00000000` 是 `amount` 列 `decimal(24,8)` 的原样输出，与同行的 `2.000000`（平台费 `setScale(6)`）精度不一致——
属既有「金额格式美化」小项，**非本轮引入、非缺陷**。）

**本轮发现并修复 / 更正**：

1. **归档后退群仍发群内回执 → 403**（已修，提交 `386a524`）：`TradeCommandHandler.reapIfDue` 在
   `tradeGroupPort.leave(chatId)`（退群）**之后**仍返回「交易群已归档（静默期满）」文本，由 `BotDispatcher`
   交给 `TelegramBotHandler.consume` 发往该群 → `403 Forbidden: bot is not a member of the supergroup chat`，
   异常落到线程池（`pool-3-thread-1`）。修法：`reapIfDue` 改为回报 `boolean`（只表达「已归档」这一事实），
   `BotDispatcher` 归档即 `return null`——公告已在归档时发到群里，不必也不该再补一条群内回执。
   端到端回归测试钉住（`BotDispatcherTest.groupMessageAfterSilenceArchives` 断言返回 `null`）；
   全量 `mvn -B clean verify` 通过（**1088 surefire + 4 IT**）。
2. **A17 原「调 0 加速」指引有误**（已在本行更正）：`TGG_TRADE_GROUP_SILENCE_DAYS=0` 使应用**启动即失败**
   （`TradeGroupService` 构造期拒绝 `PT0S`）。可行替代：把库中 `silence_started_at` 前推超过静默期。

**本轮未验（如实登记，不标 met）**：A9 降级分支（对方未与 bot 会话 → 追加「可能收不到通知」文案）、
A17 的 dispute 恢复留痕、A18 拒绝分支（非管理员 / 角色不低于自己）、A10/A11（入群订阅门禁 / 爆发保护，需频道与连续拉人）、
A3（需 BotFather 关隐私模式——`getMe` 显示 `can_read_all_group_messages=false`，即隐私模式当前**开着**）、A4/A5（inline 按钮 / 违禁词处置）。


---

## 第八次核销实录（2026-10-01 · inline 按钮交互 · 本地实例）

**范围**：本轮新增的按钮交互能力（`07539aa` / `b31be2f` / `e02a86d` / `98b4c06` / `2b1709b`）。
环境同第七次：本地 `tgg-app`（8090、`tgg_escrow_local`、`TGG_FEE_PLATFORM_RATE=0.02`），bot `@nq9831ops_bot`，**不动线上**。

| 项 | 结果 | 观察（回执逐字 / 日志） |
|---|---|---|
| 订单卡按钮渲染 | ✅ | 用户确认「按钮在」（A 侧订单卡片下方） |
| 按钮点击 → 执行等价命令 | ✅ | 日志 `11:43:43 /escrow lock`、`11:46:17 /escrow release` 均有「命令处理」记录，而用户贴文中这两条**没有命令行输入**（同批 `my`/`status`/`deliver` 都有）→ 判定为按钮触发 |
| 按状态 × 角色过滤 | ✅ | #4 → LOCKED 时 A（买方）回执**无按钮**（此时该卖方动作）；#5 → DELIVERED 时 A 的通知带**两个**按钮（验收放款 + 退款）——与 `ActionButtons` 矩阵逐格一致 |
| **通知带按钮**（`2b1709b` 新能力） | ✅ | A 收到的通知「订单 #5：对方已标记交付，请在维护期内验收（/escrow release 5）。」**下方有两个按钮**——通知既有静默语义、又带动作，两者未互相牺牲 |
| 金额去尾零（`b1897c6`/`98b4c06`） | ✅ | `/escrow my` 显示 `100 USDT`（原为 `100.00000000`）；放款回执 `平台费 2 USDT，卖方实收 98 USDT` |
| 非首单收费 | ✅ | 同上（卖方已有多笔成功交易 → 不再豁免） |
| 按钮白名单（`98b4c06` 修的真 bug） | ✅（单测 + 真机旁证） | 单击 `/escrow create <卖方ID> …` 的回执**无按钮**——修复前会把与卖方 ID 撞号的订单按钮挂上去（RED 复现：`Expecting empty but was: [ActionButton[text=💳 托管 #2, callbackData=lk:2]]`） |

**本轮发现并修复的缺陷**（均已在提交中带 RED/单测）：

1. `buttonsFor` 把 `cmd.argOpt(1)` 一律当订单号 → `create`/`confirm` 的第二参是**卖方 ID**、`invite` 是金额，**ID 撞号时会把别的订单的动作按钮挂到当前回执上**（用户点下去操作的是另一个单）。修法：白名单（只有第二参确实是订单号的子命令才挂）。`98b4c06`
2. `/escrow my` 的金额未去尾零（上一批只改了费用回执，漏了这个出口）→ 收口到共享 `MoneyFormat`。`98b4c06`
3. 通知路径不带按钮（卖方收到「待交付」仍要手打命令）→ `TradeNotifier` 改用带按钮出口；为此给 `BotReplyPort` 增了**带通知策略**的按钮发送（按钮与静默语义缺一不可）。`2b1709b`

**未核销（如实登记）**：
- 按钮在实际客户端的**换行与密度**（当前实现每行 ≤3 个）——只在少量按钮下看过，多于 3 个未验；
- `create` / `confirm` 回执的按钮（**尚未实现**：它们的第二参不是订单号，代码无法凭空得知"刚创建的订单号"，需先让回执携带新建订单 id）；
- 通知在**静默时段**的行为（未把实例时间置于静默窗口内测）；
- `A10` / `A11`（入群订阅门禁 / 爆发保护）、`A3`（需 BotFather 关隐私模式）、`A4` / `A5`——仍受外部条件阻塞。


---

## 第九次核销实录（2026-10-01 · 缺口 B + A9 降级分支 · 新 bot @nq9831opsbot）

> 背景：旧 bot token 失效（401），用户重建 bot（`@nq9831opsbot`，注意用户名**少一个下划线**）。
> 启动需同时传 `TGG_BOT_USERNAME=nq9831opsbot`（该键用于命令解析与深链，不同步会使命令带不上 bot 名）。
> 环境：本地 8090 / `tgg_escrow_local` / 费率 2%；`concurrent-max` 用代码默认 **1**。

| 项 | 结果 | 观察（回执逐字 / 日志） |
|---|---|---|
| **缺口 B**：create/confirm 回执带按钮（`c9a5b8b`） | ✅ | A：`create` → `confirm` → 回执「已创建订单 #6」**下方出现「💳 托管 #6」按钮**（用户确认「有按钮」）；点它 → 回执「订单 #6 已进入已锁仓」——贴文中 confirm 之后**无 lock 命令行**，日志 17:28:59 confirm → 17:29:18 lock → **按钮触发**。订单号不在命令参数里，由 `handle` 的回传槽带出 |
| 被拒的 confirm 不带按钮 | ✅（旁证） | 17:24:49 一次 `confirm` 被拒（`concurrent-max=1`，#5 在途占槽）——回执「被拒：已有进行中的交易」，**无按钮消息**；单测 `failedConfirmCarriesNoButtons` 同口径 |
| **A9 降级分支**（此前一直未验） | ✅ | B 未与新 bot 会话时 A `release 5` → 回执末尾真机出现「⚠️ 对方尚未与机器人开始对话，可能收不到通知，建议另行告知。」；日志同时记「交易通知：发往 u:94b6a17bf0b7 的 RELEASED 未送达」——**不误报也不漏报** |
| B 侧会话后通知恢复 | ✅ | B 对新 bot 发 `/escrow`（17:28:45）后，后续流程通知正常送达（B 的 deliver 已在第八次验过双向） |

**过程记录**：
- `concurrent-max` 默认 1（`TradeWiring.java:108`）在真机挡了一次创建——这是**准入闸设计行为**不是缺陷；测试时可配 `TGG_TRADE_CONCURRENT_MAX` 放宽。
- 本轮两条回执（#5 release、#6 lock）再次由**按钮触发**（贴文无命令行 + 日志有「命令处理」）——按钮交互已成为实际主路径。

**仍未验（如实登记）**：按钮 >3 个时的换行密度；通知在静默时段的行为；A10/A11/A3/A4/A5（外部条件阻塞）；`resolveDispute` 真链出款（需 testnet 部署，`dbfe1c1` 只完成了接缝与编码）。


---

## 第十次核销实录（2026-10-01 · ④ 信用分一次到位 · 本地实例）

**范围**：④ 三波（`155ea20` 权重配置化 / `c1348b3` 互刷不累加 / `a2085d1` 衰减 + 周榜）+ 真机逮到的缺陷修复（`eb37fe2`）。
环境：本地 8090 / `tgg_escrow_local` / `TGG_CREDIT_DECAY=30,30,0.9,0.3` / bot `@nq9831opsbot`。

| 项 | 结果 | 观察 |
|---|---|---|
| 时间衰减 + 披露（④ 差距 2） | ✅ | `/escrow credit` 出现「活跃时长：44/365 天（最近活跃距今较久，按时间衰减 ×0.729）」。**44 与 ×0.729 是按库内数据事先推算的预期值，实测逐字命中**（活跃跨度 61 天 × 0.9³） |
| 互刷不累加 + 披露（④ 差距 3） | ✅ | 本地库仅 A↔B 一对对手 → 双方集中度 100% > 0.6 → 6 笔全部剔除；回执如实披露「其中 6 笔…未计入上列统计…不代表违规认定」 |
| 零历史文案（真机逮到的缺陷） | ✅ 已修 | 修前同一条回执**自相矛盾**：既说「6 笔未计入」又说「尚无成交记录」。根因：零历史判断只看 `completed == 0`，未区分"真无交易"与"被剔成 0"。修后重跑：该段消失（`eb37fe2` + 单测 `excludedToZeroIsNotZeroHistory`） |
| 周榜惰性推送（④ 差距 4） | ✅ | 群内首条消息 → 应答后多出「📅 本周交易榜」（`8***5`/`8***3` 已脱敏）；**同周第二条不再出现**（去重生效）；**私聊只有摘要、无榜单**（私聊不推） |
| 榜单口径一致性 | ✅ | 榜单评分 41 与 `/escrow credit` 的 41 一致 → 两个出口共用同一套互刷剔除与权重口径，不是两套算法 |

**核销方法备注（供下次复用）**：
- 衰减无法用"当天数据"触发（宽限期默认 365 天、样例数据都是当天）——本次用一条**可回滚**的 UPDATE 把 6 单时间戳前移（`created_at −180d` / `updated_at −120d`），并**先取原值存档**；原值：订单 1–6 的 `created_at` 为 2026-09-30~10-01 18:42~09:29、`updated_at` 为 2026-09-30~10-01 18:45~09:29（回滚即 `+180/+120` 天）。
- 每次起实例前先 `mvn -q -DskipTests install`：单模块 `spring-boot:run` 不会重建兄弟模块，改了 `tgg-escrow` 不起 install 会跑到旧 jar。
- 停实例要**杀 JVM**（`lsof -iTCP:8090 -t`），`job kill` 只终止 maven 外壳、子 JVM 会残留并占住端口（本次踩到，报 `Port 8090 was already in use`）。

**未核销（如实登记）**：`tgg.credit.weights` 的自定义权重真机生效（单测已覆盖构造期校验与自定义权重计分）；衰减"真形态"仍后置（需按笔存时间戳）；A10/A11/A3/A4/A5 与链上项仍受外部条件阻塞。


---

## 第十一次核销实录（2026-10-01 · 防钓鱼提醒随周榜轮换 · 本地实例）

**范围**：`PhishingNotice` 接线（提交 `bf70e38`）——把账本里四条零引用（GM-13 / ET-54 / ET-69 / ET-71）接到周榜惰性推送上。

| 项 | 结果 | 观察 |
|---|---|---|
| 提醒随周榜送达 | ✅ | 群内交互 → 回执为「📅 本周交易榜」+ 榜单 + 「🛡 本周提醒：<当周文案>」 |
| **轮换位可对账** | ✅ | 独立推算：`date` 确认本周 = **ISO 第 40 周** → `40 % 3 = 1` → `NOTICES[1]` = **Comment 钓鱼**；实测文案逐字一致（「评论区里非你主动发起的「收到 USDT」提示都可能是诈骗，官方不通过评论发放奖励。」） |
| 空榜行为（设计变更） | ✅（单测） | **空榜仍推提醒**、只不发榜单：防钓鱼的价值恰在没出事时。此条**刻意偏离**了原始"空榜不推"设计，单测 `pushesNoticeEvenWhenBoardEmpty` 与代码 javadoc 都写明理由 |
| 去重边界 | ✅（已文档化） | 同群第二条消息不再推；**重启后同一周会再推一次**（去重状态在进程内，不做持久表）——本次正是靠重启来复现推送，属已知边界而非缺陷 |

**方法备注**：本轮再次用「先独立推算、再看实测」的对账方式（前两次分别用在衰减因子 ×0.729 与周榜去重上）。三次都对上，说明这三条实现的口径与推导一致，不是"看起来对"。
