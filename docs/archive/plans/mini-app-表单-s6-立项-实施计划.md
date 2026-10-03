> **Model: deepseek-flash (cheap)**

> **Status: APPROVED** — 2026-09-24T12:00:44.807Z

# Mini App 表单（S6 立项）— 实施计划

## 需求提炼

**用户原话**：
- 「用户的输入能否全部做成按钮选择样式 不使用命令」
- 选 **B：上 Mini App 表单 —— 真能做到全按钮，但要先建 Web 层**

**目标**：让用户**不再需要记命令语法**——通过 Telegram Mini App（内嵌网页表单）完成交易创建，表单可输入任意值（金额/对方 ID），绕过 inline 按钮「只能选、不能输入」的硬限制。

**非目标（YAGNI，本计划不含）**：Admin 后台界面（违禁词另有 Nginx 白名单保护）、TON 钱包连接（`tonConnect.ts`，待 S5/C1）、ET-07 的三步全流程（选角色→填信息→**确认签名**）——**MVP 只做「创建交易」一步**，其余留作后续波次。

**已确认的硬前置**：Telegram Mini App **必须 HTTPS**；服务器实测 `www.7kyuedu.xyz` **当前无证书**（`grep listen 443|ssl_certificate` 零命中）→ Wave 1 必须先解决。

## 现状锚点（开工前核对；共享工作区，行号可能漂移）

| 事实 | 锚点 |
|---|---|
| 全仓唯一控制器（风格参照） | `tgg-app/.../AdminWordController.java:49,50,56,66,71` |
| 无鉴权 / 无 CORS（全仓零命中） | `AdminWordController.java:47` 自陈 + grep 无 Security/CORS |
| Web 容器已就位，但无静态资源与配置 | `tgg-app/pom.xml:62`；grep `static-locations|WebMvcConfigurer` 零命中 |
| `TradeCommandHandler` 可注入但需适配 | `BotWiring.java:113`；签名 `handle(BotCommand, CommandActor): String` @ `TradeCommandHandler.java:103` |
| `PendingTradeRegistry` = 进程内状态 | `BotWiring.java:85`（Bot 与 Mini App 会**共享**） |
| 前端规划 9 文件、磁盘 0 个 | `web/README.md`（规划≠现状） |
| 服务器无证书 | MCP 实测 `www.7kyuedu.xyz.conf` 无 443 |

## 架构（数据流）

```mermaid
flowchart TD
  U[用户] -->|点 [打开表单] 按钮| TG[Telegram 客户端]
  TG -->|打开 HTTPS 页面| WEB["Mini App 页面（静态 HTML/JS）"]
  WEB -->|"tgWebApp.initData 原始串"| API["后端 POST /api/trade/create"]
  API --> V{"initData 验签<br/>（HMAC-SHA256）"}
  V -->|失败| R401[401 拒绝]
  V -->|成功| SVC["复用 EscrowTradeService<br/>（既有准入/风险/守卫）"]
  SVC --> DB[(MySQL)]
  API -->|JSON 结果| WEB
```

**安全命门**：`initData` 验签——不验签则任何人可伪造 `userId` 下单。规格（**待 Wave 0 对官方文档核实**，scout 已从 3 份独立实现交叉取证）：
1. 取 `Telegram.WebApp.initData` **原始串**（非 `initDataUnsafe`）
2. 按 URL-encoded query string 解析；**剔除 `hash` 字段**
3. 按 key 字典序排序，拼成 `key=value` 以 `\n` 连接 → `data_check_string`
4. `secret_key = HMAC_SHA256(key="WebAppData", msg=bot_token)`
5. `computed = HMAC_SHA256(key=secret_key, msg=data_check_string).hexdigest()`
6. **常量时间比较**（`MessageDigest.isEqual`，不用 `String.equals`——防时序侧信道）
7. 校验 `auth_date` 新鲜度（防重放）

⚠️ **已知易错点**：多篇教程把 secret_key 写成 `SHA256(bot_token)`——那是 **Login Widget** 的派生法，Mini App 用它是**错的**（验证会恒失败或引入误判）。

## Wave 0 — 探针（必须先做，结论决定实现方式）

- [ ] 0.1 **官方算法核实**：在**服务器**上 `curl -s https://core.telegram.org/bots/webapps`（服务器可访问外网，本地被沙箱 DNS 拦）→ 对 secret_key 派生方向、剔除字段、常量时间比较、HTTPS/域名要求**逐条钉死**。**若仍不可达**：标注为未核实，实现按上述 9 步（第三方交叉取证），并在代码注释里写明出处与风险。
- [ ] 0.2 **HTTPS 前置核实**：`SiteSSLApply www.7kyuedu.xyz`（Let's Encrypt，HTTP 验证；域名已解析）→ `curl -I https://www.7kyuedu.xyz` 得 200。
- [ ] 0.3 **前缀裁决**（见「待决」）：定 `/api` 还是沿用 `/admin`。

**Wave 0 验证命令**：`curl -sI https://www.7kyuedu.xyz | head -1` 返回 `HTTP/2 200`（或注明证书申请失败及原因）。

## Wave 1 — 后端 API + initData 验签（TDD，安全关键）

- [ ] 1.1 **RED**：`WebAppInitDataVerifierTest` —— 用**自造向量**（按规格自行计算签名）钉住：① 正确签名通过；② 篡改任一字段后拒绝；③ `hash` 被剔除（不含 hash 参与计算）；④ **过期 `auth_date` 拒绝**（防重放）；⑤ 签名错误不抛异常而是返回空/拒绝（fail-closed）。
- [ ] 1.2 **GREEN**：新增 `WebAppInitDataVerifier`（tgg-app，注入 bot token；`MessageDigest.isEqual` 比较）。
- [ ] 1.3 **RED→GREEN**：`TradeApiControllerTest`（`@WebMvcTest` 或直接测 controller 方法）—— ① 验签失败 → 401；② 验签成功 → 装配 `BotCommand`+`CommandActor` 调 `TradeCommandHandler` → 返回结构化 JSON；③ 参数非法 → 400（**不是**把 USAGE 文案当成功）。
- [ ] 1.4 **GREEN**：新增 `TradeApiController`（`@RestController`，照 `AdminWordController` 风格：构造注入 + null fail-fast + `ResponseEntity<Map<String,Object>>`）。**入参用 DTO**，绝不把 `CommandActor` 直接暴露成 JSON。
- [ ] 1.5 全量回归 + 提交。

**Wave 1 验证命令**：`mvn test > /tmp/w1.log 2>&1; echo MVN_EXIT=$?` → `BUILD SUCCESS` 且 `MVN_EXIT=0`（基线 572）。

## Wave 2 — 前端表单（静态页，不引构建链）

- [ ] 2.1 新增 `tgg-app/src/main/resources/static/miniapp/index.html`（**原生 HTML + JS**，零构建——YAGNI：Vue 留给后续，表单不需要）；引入 Telegram 官方 `telegram-web-app.js`；读 `initData` 后 `fetch('/api/trade/create', {body: initData + 字段})`。
- [ ] 2.2 页面遵守 `10-交互细节.md`：跟随 `themeParams`、`BackButton` 映射、**文字自带语义**。
- [ ] 2.3 验证：本地 `mvn test` 通过 + 静态资源能被 Spring 服务（Wave 4 实测）。

**Wave 2 验证命令**：`curl -sI http://127.0.0.1:8080/miniapp/index.html`（部署后）返回 200。
说明：该路径对应 Wave 2.1 **新增** 的文件 `tgg-app/src/main/resources/static/miniapp/index.html`
（Spring 默认把 `classpath:/static/**` 映射到根路径）；它当前**不存在**于仓库，本波次创建。

## Wave 3 — Bot 挂 WebApp 按钮

- [ ] 3.1 **RED**：`TelegramBotHandlerTest` 增例——收到 `/escrow`（无子命令）时回带 **WebApp 按钮**的消息（断言 `SendMessage` 的 `replyMarkup` 含 `web_app`），而非纯文本用法说明。
- [ ] 3.2 **GREEN**：`BotReplyPort` 增 `sendTextWithWebApp(chatId, text, url)`（**接口变更 → 同步全部消费方**：适配器 + 测试 fake）。
- [ ] 3.3 全量回归 + 提交。

**Wave 3 验证命令**：`mvn test`（基线 572 + 新增）→ `MVN_EXIT=0`。

## Wave 4 — 部署 + 真机验证

- [ ] 4.1 构建 → **增量补丁上传**（若仅类变更；**若新增静态资源必须全量或补投 static/**）→ 重启。
- [ ] 4.2 **C3 真机验证**（`ONLINE-VERIFICATION.md`）：iPhone/Android 各一，私聊 bot 点按钮 → 打开 Mini App → 提交表单 → 订单落库。
- [ ] 4.3 回写 `ONLINE-VERIFICATION.md`。

**Wave 4 验证命令**：`MysqlQuery` 查到新订单行 + 真机截图（用户操作）。

## 反证 / 复现（瑶光反证）

**计划期已复现**：
- 服务器**无 HTTPS**：MCP 实测 conf 无 443/证书（file 级）。
- `AdminWordController` 无鉴权、全仓无 CORS：grep 零命中。
- `web/README.md` 规划的 9 文件**磁盘为 0**：glob 实测。
- `PendingTradeRegistry` 为进程内共享状态：读 `BotWiring.java:85` + handler 用法。
- 旧部署 jar 与新代码的差异面（8 类 + 1 迁移）。

**计划期无法复现、列为待验证假设（由 Wave 0 证伪）**：
- **initData 验签算法**（9 步）——官方文档在本地沙箱**不可达**，scout 仅从第三方实现交叉取证，**未对官方原文核验**。若算法有偏差，Wave 1 的自造向量测试**验不出**（它验的是"实现符合我写的规格"，不是"符合 Telegram"）→ **故 Wave 0.1 必须在服务器上取官方原文，否则 Wave 1 的绿色不构成安全证明**。
- WebApp URL 是否必须注册域名（vs 纯 IP）——未证实。
- `InlineKeyboardButton.web_app` 的准确字段名/仅私聊限制——第三方文档，未对官方核验。

**失败分流**：Mini App 打不开时，先分清是「HTTPS 不通」「按钮没挂上」还是「页面 404」——三者症状都是"点了没反应"，需分别取 `curl -I https://…`、日志中的 SendMessage、`curl …/miniapp/index.html`。

## 待决（需用户拍板）

1. **API 前缀**：`web/README.md` 规划 `/api`，现有端点是 `/admin/words`——交易类端点挂 `/admin` 语义不符（且 `/admin` 已被 Nginx 全局 deny）。**建议 `/api/` 并在 Nginx 单独放行（带验签，无需 IP 白名单）**。
2. **跨入口行为**：Bot 与 Mini App 共享同一个 `PendingTradeRegistry`，意味着用户可能「在 Bot create、在 Mini App confirm」。ET-34「风险提示不可绕过」是按**同一入口内** create→confirm 设计的。**建议**：Mini App 表单自带风险提示展示，视为满足 ET-34；不接受跨入口拼单（需要额外隔离，成本高）。
3. **范围确认**：MVP 只做「创建交易」，不含 Admin 界面与钱包——认可否？
