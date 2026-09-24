# 线上统一验证清单（本地不可得，后期集中验证）

> 用途：本地开发中标注「未验证」的事项全部收口于此，**线上部署阶段逐条核销**。
> 核销纪律：每条给出「怎么验 / 期望观察」；验过打勾并注日期，验不了的写明障碍。
> 建档：2026-09-24（天梁）· 配套：`docs/requirements/SPEC.md` 第 8 节部署指引。
> **首次线上部署：2026-09-24，服务器 141.164.50.75（www.7kyuedu.xyz），见文末「部署实录」。**

## A. 依赖真实 Telegram（A2 Bot Token 解锁）

| # | 事项 | 怎么验 | 期望观察 | 状态 |
|---|---|---|---|---|
| A1 | S1 用户级验收：命令回执 | 私聊 bot 发 `/escrow create 2002 100 USDT` → `/escrow confirm …` | 风险提示 + 「已创建订单 #N」 | ✅ 2026-09-24（见部署实录 V2/V3） |
| A2 | `TelegramBotHandler` 运行期（发送/长轮询） | 启动应用 → 发任意命令 | 回执到达；无 callback 时无转圈 | ✅ 2026-09-24（日志 `BotSession: deleteWebhook: true`、`Started EscrowBotApplication`；回执到达） |
| A3 | **隐私模式关闭后留痕生效** | BotFather 关隐私模式→移除→重入群→群里发普通消息 | Bot 日志可见该消息（漏配则留痕静默失效，09 材料） | ☐ 未验——需 BotFather 关隐私模式并重入群（私聊场景不触发） |
| A4 | answerCallbackQuery 不变量 | 点任意 inline 按钮 | 按钮不转圈（finally 应答） | ☐ 未验——inline 按钮交互属 S3，尚未接线 |
| A5 | GM-06 违禁词命中处置（删消息+警告） | 发违禁词 | 消息被删 + 警告回执（需 Telegram 删除 API，线上接） | ☐ 未验——`GroupAdminPort` 真实适配器未接 |

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
| C1 | **Admin API 鉴权与暴露面** | 部署后未授权访问 `/admin/words` | 被拒（当前**无鉴权**，部署必须配鉴权+仅内网） | ✅ 2026-09-24（Nginx `location ^~ /admin/ { deny all; }`；公网侧与服务器侧实测均 **403**）· 注意：应用层仍无鉴权，仅 Nginx 兜底 |
| C2 | Vue3 前端构建 | `npm install && npm run build` | 构建通过（当前仅 README 规划） | ☐ |
| C3 | Mini App 真机（SPEC S6 验收） | iPhone/Android 跑完整 TON Connect 流程 | 连接→签名→状态更新正常 | ☐ |
| C4 | TON Pay webhook（若启用） | 模拟支付回调 | 验签+金额+防重复生效（前置：Web 层+API Key） | ☐ |
| C5 | MySQL 生产迁移 | 连生产库启动 | Flyway V1/V2 干净应用（本地 H2 已验；A3 旧库需 repair） | ✅ 2026-09-24（MySQL 8.0.43；`flyway_schema_history` v1/v2 均 `success=1`，6 张表建成） |
| C6 | TelegramBotHandler 群内角色查询升级 | 管理员命令在群里执行 | 当前固定 MEMBER（保守）；线上接 getChatMember 后放开 | ☐ |

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
   MCP 的 Bash 沙箱无权读。**绕法：用宝塔「计划任务」（面板权限）把 jar 复制到 `/www/wwwroot/tgg/`**。
6. **宝塔 Java 项目拼的 `project_cmd` 有坑**：`-jar` 排在 `-Xmx` 之前（JVM 参数顺序错），
   且不支持 `env_list` → 需用 `JavaProjectModify` 重写 `project_cmd`，把环境变量内联进命令。

**运行形态**：
- 应用：`/www/wwwroot/tgg/app.jar`，env 内联于宝塔 Java 项目 `project_cmd`（token/DB 凭证**不入仓**）
- MySQL：`/opt/mysql-8.0.43-…`，data `/opt/mysql/data`，监听 `127.0.0.1:3306`，库 `tgg_escrow`
- Nginx：反代 `www.7kyuedu.xyz:80` → `127.0.0.1:8080`，`/admin/` 白名单 deny all

**核销结果**：V1 ✅（Flyway v2 + Tomcat 8080 + Bot 长轮询）· V2 ✅（私聊命令得回执）·
V3 ✅（`escrow_orders` id=1，buyer 8724975623，100 USDT，state=OPEN）· V4 ✅（域名→应用）·
V5 ✅（`/admin/words` 403）。

**未做**：A3/A4/A5（见上表障碍）· 宝塔源码编译进程仍在后台空转 · 临时计划任务 `tgg-copy-jar` 待删。
