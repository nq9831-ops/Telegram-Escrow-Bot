# 线上统一验证清单（本地不可得，后期集中验证）

> 用途：本地开发中标注「未验证」的事项全部收口于此，**线上部署阶段逐条核销**。
> 核销纪律：每条给出「怎么验 / 期望观察」；验过打勾并注日期，验不了的写明障碍。
> 建档：2026-09-24（天梁）· 配套：`docs/requirements/SPEC.md` 第 8 节部署指引。

## A. 依赖真实 Telegram（A2 Bot Token 解锁）

| # | 事项 | 怎么验 | 期望观察 | 状态 |
|---|---|---|---|---|
| A1 | S1 用户级验收：命令回执 | 群里发 `/escrow create 2002 100 USDT` | 看到「已创建订单 #N」或「被拒：<原因>」 | ☐ |
| A2 | `TelegramBotHandler` 运行期（发送/长轮询） | 启动应用 → 发任意命令 | 回执到达；无 callback 时无转圈 | ☐ |
| A3 | **隐私模式关闭后留痕生效** | BotFather 关隐私模式→移除→重入群→群里发普通消息 | Bot 日志可见该消息（漏配则留痕静默失效，09 材料） | ☐ |
| A4 | answerCallbackQuery 不变量 | 点任意 inline 按钮 | 按钮不转圈（finally 应答） | ☐ |
| A5 | GM-06 违禁词命中处置（删消息+警告） | 发违禁词 | 消息被删 + 警告回执（需 Telegram 删除 API，线上接） | ☐ |

## B. 依赖真实链（S5 合约 + C1 定案 + D 核实）

| # | 事项 | 怎么验 | 期望观察 | 状态 |
|---|---|---|---|---|
| B1 | `EscrowContract.tolk` 编译 | Blueprint/Acton build | 编译通过（当前**仅源码+注释**） | ☐ |
| B2 | Gas 基线报告 | `blueprint test --gas-report --snapshot` | 出基线，后续版本对比 | ☐ |
| B3 | **乱序消息不越级迁移**（ET-58） | 合约测试：confirm/deliver 同时发 | 状态机不越级（SPEC S5 验收） | ☐ |
| B4 | 交付证明哈希加盐（ET-57） | 提交常见短语 proofHash | 不可字典反推 | ☐ |
| B5 | 链上事件/资金验证（ET-19/59 + S4） | `HttpChainSource` 双源对账 | 交叉验证一致（D：TON 确认数语义须先核实） | ☐ |
| B6 | 合约审计（ET-20） | 第三方审计 | 审计报告归档 | ☐ |

## C. 依赖线上环境（部署/网络/构建工具链）

| # | 事项 | 怎么验 | 期望观察 | 状态 |
|---|---|---|---|---|
| C1 | **Admin API 鉴权与暴露面** | 部署后未授权访问 `/admin/words` | 被拒（当前**无鉴权**，部署必须配鉴权+仅内网） | ☐ |
| C2 | Vue3 前端构建 | `npm install && npm run build` | 构建通过（当前仅 README 规划） | ☐ |
| C3 | Mini App 真机（SPEC S6 验收） | iPhone/Android 跑完整 TON Connect 流程 | 连接→签名→状态更新正常 | ☐ |
| C4 | TON Pay webhook（若启用） | 模拟支付回调 | 验签+金额+防重复生效（前置：Web 层+API Key） | ☐ |
| C5 | MySQL 生产迁移 | 连生产库启动 | Flyway V1/V2 干净应用（本地 H2 已验；A3 旧库需 repair） | ☐ |
| C6 | TelegramBotHandler 群内角色查询升级 | 管理员命令在群里执行 | 当前固定 MEMBER（保守）；线上接 getChatMember 后放开 | ☐ |

## D. 待用户拍板后才能定案（非验证项，阻塞对应开发）

B1 费率 / B2 首单形态 / B3 信用分 5 点澄清 / B4 KYC 口径 / C1 多签语义 / D TON 确认数语义 /
M Agentic Wallets / 排名范围（群内/跨群）——见 `docs/UNDECIDABLE-DECISIONS.md`。
