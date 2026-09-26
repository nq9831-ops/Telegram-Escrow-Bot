# 线上统一验证清单（本地不可得，后期集中验证）

> 用途：本地开发中标注「未验证」的事项全部收口于此，**线上部署阶段逐条核销**。
> 核销纪律：每条给出「怎么验 / 期望观察」；验过打勾并注日期，验不了的写明障碍。
> 建档：2026-09-24（天梁）· 配套：`docs/requirements/SPEC.md` 第 8 节部署指引。
> **首次线上部署：2026-09-24，服务器 141.164.50.75（www.7kyuedu.xyz）。**
> **第二次部署（同日）：T2 查询 + cancel + 乐观锁上线，见文末「第二次部署实录」。**

## A. 依赖真实 Telegram（A2 Bot Token 解锁）

| # | 事项 | 怎么验 | 期望观察 | 状态 |
|---|---|---|---|---|
| A1 | S1 用户级验收：命令回执 | 私聊 bot 发 `/escrow create 2002 100 USDT` → `/escrow confirm …` | 风险提示 + 「已创建订单 #N」 | ✅ 2026-09-24（订单 id=1 落库，state=OPEN） |
| A2 | `TelegramBotHandler` 运行期（发送/长轮询） | 启动应用 → 发任意命令 | 回执到达；无 callback 时无转圈 | ✅ 2026-09-24（`BotSession: deleteWebhook: true`、`Started EscrowBotApplication`；回执到达） |
| A3 | **隐私模式关闭后留痕生效** | BotFather 关隐私模式→移除→重入群→群里发普通消息 | Bot 日志可见该消息（漏配则留痕静默失效，09 材料） | ☐ 未验——需 BotFather 关隐私模式并重入群（私聊场景不触发） |
| A4 | answerCallbackQuery 不变量 | 点任意 inline 按钮 | 按钮不转圈（finally 应答） | ☐ 未验——inline 按钮交互属 S3，尚未接线 |
| A5 | GM-06 违禁词命中处置（删消息+警告） | 发违禁词 | 消息被删 + 警告回执（需 Telegram 删除 API，线上接） | ☐ 未验——`GroupAdminPort` 真实适配器未接 |

## A'. 交易命令端到端（第二次部署引入的能力）

| # | 事项 | 怎么验 | 期望观察 | 状态 |
|---|---|---|---|---|
| A6 | **`/escrow cancel` 端到端** | 私聊发 `/escrow cancel 1` | 回执「已取消」且**库里 state 变 CANCELLED** | ✅ 2026-09-24 实测：`escrow_orders` id=1 → `state=CANCELLED`、`version=0→1`、`reason=当事人取消` |
| A7 | **乐观锁在生产库生效** | 同上（观察 version 列） | 写入后 `version` 自增 | ✅ 2026-09-24：`version 0 → 1`（证明 `@Version` 在真库上工作） |
| A8 | `/escrow status` 生产回执 | 私聊发 `/escrow status 1` | 回订单号 + 状态摘要 + 下一步 | ⚠️ **部分验证**：同链路（dispatcher→handler）已由 A6 端到端证通，且本地 `TradeCommandHandlerTest` 覆盖三条 status 用例；但**生产回执未亲眼确认**（status 只读，DB 不留痕，用户未回报文） |
| A9 | **对手方主动通知** | 甲私聊 bot 走 `/escrow confirm` → `/escrow lock`，乙（卖方）应在私聊收到通知；再让乙发 `/escrow deliver`，甲应收到 | 对方收到通知；**命令路径**在对方未与 bot 会话过时，发起方回执追加「对方可能收不到通知」；**Web 路径**（Mini App）同情形下只返回 `notified:false`、不产生文案（前端据此提示） | ☐ **未验**——通知路径已由 `TradeNotifierTest` / `TradeCommandHandlerTest` / 两个 Web 控制器测试以记录型出口覆盖，但**真实 Telegram 发送无 token 无法验**；Bot 能否主动私聊未会话过的用户亦未核实 |

## B. 依赖真实链（S5 合约 + C1 定案 + D 核实）

| # | 事项 | 怎么验 | 期望观察 | 状态 |
|---|---|---|---|---|
| B1 | `EscrowContract.tolk` 编译 | `acton build` | 编译通过 —— ✅ 2026-09-25 接入 Acton v1.2.0，`acton build` 与 `acton test`（2 passed）均通过（**骨架阶段**：资金逻辑待 C1 定案；commit 5535e32） | ✅ |
| B2 | Gas 基线报告 | `acton test --gas-profile <FILE>`（或 `[test] gas-profile` 配置） | 出基线，后续版本对比 | ☐ |
| B3 | **乱序消息不越级迁移**（ET-58） | 合约测试：confirm/deliver 同时发 | 状态机不越级（SPEC S5 验收） | ☐ |
| B4 | 交付证明哈希加盐（ET-57） | 提交常见短语 proofHash | 不可字典反推 | ☐ |
| B5 | 链上事件/资金验证（ET-19/59 + S4） | `HttpChainSource` 双源对账 | 交叉验证一致（D：TON 确认数语义须先核实） | ☐ |
| B6 | 合约审计（ET-20） | 第三方审计 | 审计报告归档 | ☐ |

## C. 依赖线上环境（部署/网络/构建工具链）

| # | 事项 | 怎么验 | 期望观察 | 状态 |
|---|---|---|---|---|
| C1 | **Admin API 鉴权与暴露面** | 部署后未授权访问 `/admin/words` | 被拒（当前**无鉴权**，部署必须配鉴权+仅内网） | ✅ 2026-09-24（Nginx `location ^~ /admin/ { deny all; }`；公网侧与服务器侧实测均 **403**）· 注意：应用层仍无鉴权，仅 Nginx 兜底 |
| C2 | Vue3 前端构建 | `npm install && npm run build` | 构建通过（当前仅 README 规划） | ☐ |
| C3 | Mini App 真机（SPEC S6 验收） | iPhone/Android 跑完整 TON Connect 流程 | 连接→签名→状态更新正常 | ☐ |
| C4 | TON Pay webhook（若启用） | 模拟支付回调 | 验签+金额+防重复生效（前置：Web 层+API Key） | ☐ |
| C5 | MySQL 生产迁移 | 连生产库启动 | Flyway V1/V2 干净应用（本地 H2 已验；A3 旧库需 repair） | ✅ 2026-09-24（MySQL 8.0.43；v1/v2 均 `success=1`）· **V3 亦已应用**（见第二次部署） |
| C6 | TelegramBotHandler 群内角色查询升级 | 管理员命令在群里执行 | 当前固定 MEMBER（保守）；线上接 getChatMember 后放开 | ☐ 未验 |

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
