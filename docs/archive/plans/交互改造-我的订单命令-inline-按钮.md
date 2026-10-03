> **Model: deepseek-flash (cheap)**

> **Status: EXECUTED** — 2026-09-30（三波全部交付，见文末「闭环摘要」）

# 交互改造：`/escrow my` + inline 按钮（Wave 1–3）

## 需求提炼

**用户原话**：「和用户交互纯命令太复杂了，可以改为按钮交互式吗？按钮简洁，用户不用知道命令就可以上手操作」；并追问三个边界——「群突然恶意解散了怎么办」「交易期间把机器人踢出去」「用户删除聊天记录」，以及「双方都在一个群里，是否所有操作只在群里处理——用户私聊机器人不回复？」

**目标**（用户已采纳的推荐方案）：
1. **新增 `/escrow my`**：列出我参与的订单（含状态与脱敏对手方）。解决「删了聊天记录就找不回订单号」——现有 12 条命令**全都要先知道订单号**，没有任何按用户列举的入口。
2. **订单回执携带 inline 按钮**：只显示「当前角色此刻可执行」的动作，用户不必记命令。
3. **保留私聊通道**：不改成「只在群处理」（群 = 见证/留痕，私聊 = 敏感操作）。

**非目标**：
- 不为「群解散 / bot 被踢」加处理逻辑——`TradeGroupPort` 契约已是 fail-safe（失败不得静默，但交易主流程不回滚）。
- 不引入调度器 / 定时推送（项目既定非目标）。
- 不改既有 12 条命令的语义——**按钮与命令严格等价**，只是更友好的入口。
- 不做新前端页面（Mini App 仍只有表单页）。

## 现状（实测，带锚点）—— 决定改造面

| 接缝 | 现状 | 锚点 |
|---|---|---|
| 回执模型 | 只有 `text` + `offerWebApp` 布尔，**表达不了按钮组** | `tgg-app/src/main/java/com/tg/escrow/BotReply.java:44`（record 头）、`:46`/`:51`（工厂） |
| 回复出口 | 有 `sendText*` / `sendTextWithWebApp` / `ackCallback`，**无「带任意按钮」** | `BotReplyPort.java:34-67` |
| 按钮构造能力 | 已有 `InlineKeyboardMarkup` / `InlineKeyboardButton`（现仅用于表单按钮） | `TelegramBotReplyAdapter.java:95-104` |
| callback 路由 | **只应答，不执行动作** | `TelegramBotHandler.java:258`；类注释原话「按钮交互的命令扩展在 S3 接入；当前 callback 仅应答」 |
| 订单查询 | 只有 `byId`；仓储**全是聚合**（count/max/min/distinct），**无列表查询** | `EscrowOrderLookupPort.java:46`；`EscrowOrderRepository.java` 全文 |
| 命令入口 | 12 条 `SUB_*`，**无「我的订单」** | `TradeCommandHandler.java:91-104` |
| `new BotReply(` 调用点 | **仅两个工厂内部** → record 加字段零外部波及 | grep 实测：`BotReply.java:46,51` |

## 设计

```mermaid
flowchart TD
    U[用户] -->|文本命令| H[TelegramBotHandler.consume]
    U -->|点击 inline 按钮| H
    H -->|callbackData| AC[ActionCodec 纯函数<br/>verb:orderId ↔ BotCommand]
    AC --> D[BotDispatcher.handle]
    H -->|文本| D
    D --> T[TradeCommandHandler<br/>既有守卫：仅买方 / 仅卖方 / 状态]
    T -->|BotReply + ActionButton[]| H
    H -->|sendTextWithButtons| TG[Telegram]
    T -->|recentFor userId| R[EscrowOrderRepository<br/>新增列表查询]
```

- **按钮数据格式**：`<verb>:<orderId>`，verb ∈ `{st,lk,dl,rl,rf,dp}`（例 `rl:3` = 4 字节 ≪ Telegram 64B 上限）。
- **权限模型（核心不变量）**：按钮**不是授权**——callback 的 actor 取 `callbackQuery.from.id`（+ `memberRolePort.roleOf`），走**同一** `TradeCommandHandler` 守卫链。刚真机验过的「仅买方可验收 / 仅卖方可交付 / 状态守卫」照旧生效。
- **发送优先级**：`buttons 非空 → sendTextWithButtons` › `offerWebApp → sendTextWithWebApp` › `sendText`。
- **误点防护**：按钮文字自带动作与订单号（如 `✅ 验收放款 #3`）；动作语义与命令**完全一致**（不引入「点一下先确认」的新语义）。

## Wave 1 · `/escrow my`（纯文本，先落地「找得回」）

1. `EscrowOrderLookupPort` 加 `List<EscrowOrder> recentFor(long userId, int limit)`（读侧新语义；`byId` 不动）。
2. `EscrowOrderRepository` 加 `findByBuyerUserIdOrSellerUserIdOrderByUpdatedAtDesc(...)`；`JpaEscrowOrderLookup` 实现（limit 夹取，防超大值）。
3. 新增 `MyOrdersView`（`tgg-app`）：每行 `#id · 状态中文 · 金额 币种 · 对手方(脱敏) · 更新时间`；空列表给可读文案；对手方走 `RankPrivacy.display(...)`（与榜单同源脱敏）。
4. `TradeCommandHandler`：`SUB_MY = "my"` + `handleMy(actor)` + 用法文案加一行。
5. 测试：`MyOrdersViewTest`（空/单/多/脱敏）+ `TradeCommandHandlerTest`（`/escrow my` 回执、只见自己的单）。

**验证**：`mvn -B -pl tgg-escrow,tgg-app -am test -Dtest='MyOrdersViewTest,TradeCommandHandlerTest' -Dsurefire.failIfNoSpecifiedTests=false`

## Wave 2 · 按钮基础设施（不改变任何现有行为）

1. `BotReply` 加 `List<ActionButton> buttons` + 新 record `ActionButton(String text, String callbackData)`；`plain`/`withWebApp` 置空列表（**外部零改动**）；新增 `withActions(text, buttons)`。
2. `BotReplyPort` 加 `sendTextWithButtons(long chatId, String text, List<ActionButton> buttons)`。
3. `TelegramBotReplyAdapter` 实现（构造 `InlineKeyboardMarkup`；`callbackData` 非空且 ≤64 字节，超限**抛异常**——fail-fast 不静默截断）。
4. 新增 `ActionCodec`（纯函数）：`encode(verb, orderId)` / `decode(String) → Optional<BotCommand>`；未知 verb / 畸形输入返回 empty（不抛）。
5. `TelegramBotHandler`：发送分支加 buttons 优先级；callback 分支——解码成功则以 `from.id` 构造 actor（+ `memberRolePort`）调 dispatcher 并把回执发回**点击所在 chat**；解码失败只 ack。**既有不变量保持**：每个 callback 必应答（finally）。
6. 测试：`ActionCodecTest`（往返 + 畸形输入）+ `TelegramBotReplyAdapterTest`（markup 构造 + 超限拒绝）+ `TelegramBotHandlerTest`（点按钮 → 动作真的发生；且**不是点按钮就能越权**）。

**验证**：`mvn -B -pl tgg-app -am test -Dtest='ActionCodecTest,TelegramBotReplyAdapterTest,TelegramBotHandlerTest' -Dsurefire.failIfNoSpecifiedTests=false`

## Wave 3 · 把按钮挂到订单回执上

1. 新增 `ActionButtons`（`tgg-app`，纯函数）：`forOrder(order, userId)` 按「状态 × 角色」决定显示哪些按钮。
2. `TradeCommandHandler`：`lock/deliver/release/refund/dispute` 成功回执 + `/escrow status` 回执挂按钮；`/escrow my` 每单挂「查看」按钮（`st:<id>`）。
3. 测试：`ActionButtonsTest`（状态×角色矩阵）+ `TradeCommandHandlerTest`（各回执的按钮集合）。

**验证**：全量 `TELEGRAM_BOT_TOKEN=123456:dummy-for-it TGG_DB_NAME=tgg_escrow_verify mvn -B clean verify`（基线 **1088 surefire + 4 IT**，不得回退）

## 反证 / 复现

计划期已**回读原文**核实以下关键断言（决定改造面，非推论）：

- `BotReply` 只承载布尔意图 → `BotReply.java:44`；`new BotReply(` 全仓仅 `:46`/`:51`（grep 实测）→ **record 加字段零外部波及**。
- `BotReplyPort` 无带按钮方法 → `BotReplyPort.java:34-67` 全文方法表。
- 仓储无列表查询 → `EscrowOrderRepository.java` 全文（只有 `count*` / `findMax*` / `findMin*` / `distinct*`）。
- `EscrowOrderLookupPort` 只有 `byId` → 该文件全文。
- callback 当前只 ack → `TelegramBotHandler.java:258` + 类注释原文。
- 命令表无「我的订单」→ `TradeCommandHandler.java:91-104` 的 12 个 `SUB_*` 常量。

**待验证假设（不当结论）**：
- Telegram inline 按钮「每行数量/总行数」的实际上限——本轮按 ≤5 按钮/单设计，远低于官方上限；真机核销时以实际渲染为准。
- **群内 callback 的 chatId 来源**：实现取 `query.getMessage().getChatId()`；若某些客户端形态下为空，回退用 `from.id` 私聊发送。**此为真机核销首选验证点。**

## 回归清单（不得回退）

`BotDispatcher` 的命令路由与内容安全顺序 · `TradeCommandHandler` 的 12 条命令语义 · `UNREACHABLE_HINT` 不误报 · `reapIfDue` 归档后不响应（`386a524`）· `TradeNotifier` 失败不回滚 · 私聊/群双通道（A1/A8/A9 真机已验）· **每个 callback 必应答**的不变量。


---

## 闭环摘要（2026-09-30）

**状态**：EXECUTED · 交付门 GREEN

| 波 | 提交 | 内容 |
|---|---|---|
| Wave 1 | `07539aa` | `/escrow my`——端口 `recentFor` + 仓储列表查询 + `MyOrdersView` + 命令分支 |
| Wave 2 | `b31be2f` | 按钮基础设施——`BotReply.ActionButton`（64 字节 fail-fast）+ `BotReplyPort.sendTextWithButtons` + `ActionCodec` + `TelegramBotHandler` callback 路由 |
| Wave 3 | `e02a86d` | `ActionButtons` 状态×角色矩阵 + `handleReply` 入口挂按钮 + `/escrow my` 查看按钮 |
| 文档 | `4f4260f` | README 能力清单同步 |

**验证**：`TELEGRAM_BOT_TOKEN=123456:dummy-for-it TGG_DB_NAME=tgg_escrow_verify mvn -B clean verify`
→ BUILD SUCCESS，**1114 surefire + 4 IT 全绿**（基线 1088 → +26：Wave1 5 / Wave2 12 / Wave3 9）。

**执行中的偏离与补充**（计划未写到、执行时发现）：

1. **`EscrowOrderLookupPort` 加方法的影响面比计划预估大**：除 1 个 JPA 实现外，还有 **4 个测试替身 + 2 处 lambda 实现**（`AdminVerdictControllerTest` / `AdminVerdictEndpointAcceptanceTest` 把它当函数接口用，加第二个方法后不再是函数接口 → 必须改匿名类）。用 `mvn test-compile` 让编译器列全，没靠 grep 猜。
2. **`EscrowOrder` 的回填方法是 `assignId` 而非 `setId`**（测试造数时踩到）。
3. **`BotReplyPort` 加方法的波及面**：1 个生产实现 + **5 个测试替身**（同法用编译器定位）。
4. **子包 import**：`TradeApiControllerTest` / `TradeInviteControllerTest` 在 `com.tg.escrow.webapp`，引用 `BotReply` 需全限定。
5. **Wave 3 生效的副作用**：带按钮回执改走 `sendTextWithButtons` → `TelegramBotHandlerTest.RecordingReply` 不再记 `texts` → 2 个既有断言失败；修法是让替身两条通道都记——这反过来**证明了按钮真的生效**。

**未做（如实登记）**：
- **真机核销**：按钮点击链路、群内 callback 的 `chatId` 取值（计划里的首选验证点）、按钮在实际客户端的渲染（每行 3 个是否合适）**均未验**。
- 非订单类命令（如 `/escrow rank`）的回执未挂按钮——本波只覆盖订单类。
