# 快速开始（构建 · 测试 · 启动）

> 目标：从 clone 到跑起来的最短路径。全部命令在本仓库实测过（2026-10-03，macOS + JDK 21 + Maven 3.9）。
> 「该信哪份文档」的文档地图见 [`docs/INDEX.md`](INDEX.md)。

---

## 0. 前置

| 需要什么 | 版本/说明 | 用在哪 |
|---|---|---|
| JDK | **21**（pom 已锁；换版本前先读 `pom.xml` 里 `java.version` 的注释） | 构建与运行 |
| Maven | 3.9+ | 构建 |
| MySQL | 8.x（**可选**） | 只有集成测试（`*IT`）与真实运行需要；单元测试零外部依赖 |
| Acton | 1.2.0（**可选**） | 只有改 `contracts/` 合约时才需要，见 `Acton.toml` |

## 1. 构建 + 单元测试（零外部依赖，最快的一步）

```bash
mvn -B test
```

本机实测约 25 秒跑完全部 6 个模块（约 1100 个测试；首次构建需先下载依赖，会久一些）。
这一步**不需要数据库、不需要任何环境变量、不连外网服务**。
（精确基线与复算命令见 `docs/CODE-VS-SPEC.md` §5。）

## 2. 全量验证（含集成测试，需要 MySQL）

集成测试（`*IT`）会连真库并启动完整 Spring 上下文，比单元测试多两个前置
（完整说明见 `tgg-app/src/test/java/com/tg/escrow/EscrowPersistenceIT.java` 的「运行前提」）：

- 一个可达的 MySQL（连接参数走 `TGG_DB_*` 环境变量，默认 `localhost:3306` / `root` / 空密码）；
- 一个**非空**的 `TELEGRAM_BOT_TOKEN`——集成测试不连 Telegram，但 `BotTokenConfig`
  在 bean 创建期校验非空，**值可以任意假**。

```bash
TGG_DB_NAME=tgg_verify TELEGRAM_BOT_TOKEN=123456:dummy-for-it mvn -B clean verify
```

- ⚠️ **接口/签名变更后必须 `clean`**：Maven 增量编译会把「未改动但接口已变」的旧 class
  混过编译、报出假的 BUILD SUCCESS（本仓有先例，见 `docs/CODE-VS-SPEC.md` §5）。
- ⚠️ Flyway checksum 失配（`FlywayValidateException`——库建在迁移脚本被修改之前）：换一个
  空库名（`TGG_DB_NAME=<新库名>`）即可从零迁移，不必 repair。
- 只想跑某一个集成测试：

```bash
TGG_DB_NAME=tgg_verify TELEGRAM_BOT_TOKEN=123456:dummy-for-it \
  mvn -B -pl tgg-app -am verify -Dit.test=EscrowPersistenceIT -DfailIfNoTests=false
```

## 3. 单模块 / 单个测试（改哪跑哪）

```bash
mvn -B -pl tgg-core -am test                            # 只跑 tgg-core 及其上游
mvn -B -pl tgg-app -am test -Dtest=BotDispatcherTest    # 只跑一个测试类
```

## 4. 启动应用（本地最小路径）

先打包（跳过测试）：

```bash
mvn -B -pl tgg-app -am package -DskipTests
```

再启动。`TELEGRAM_BOT_TOKEN` 与 `TELEGRAM_BOT_USERNAME` 是**必备键**（缺任一启动直接失败，
失败信息会点名缺的键）；其余按需：

```bash
TELEGRAM_BOT_TOKEN=<你的 token> TELEGRAM_BOT_USERNAME=<你的 bot 用户名> \
TGG_DB_NAME=tgg_escrow SERVER_PORT=8080 \
java -jar tgg-app/target/tgg-app-0.1.0-SNAPSHOT.jar
```

- 全部配置项（67 键 × 默认值 × 行为说明）见 `docs/CONFIG-KEYS.md`；
- 完整体验流程（Telegram 里走通部署→入金→交付→出款，含 Mini App / HTTPS 隧道 /
  HTTP 代理配置）见 `docs/LIVE-TEST-RUNBOOK.md`——那是逐步骤的真机手册，本页只负责「先跑起来」。

## 5. 模块地图

| 模块 | 职责 |
|---|---|
| `tgg-common` | 异常基类（`EscrowException` / `TggException`） |
| `tgg-core` | 领域纯逻辑（守卫 / 准入 / 风控 / 信用判定，无 IO） |
| `tgg-federation` | 联邦签名（Ed25519） |
| `tgg-escrow` | 交易领域 + JPA 持久化 + Flyway 迁移（V1–V11） |
| `tgg-chain` | TON 链交互（钱包 / 部署 / 存证 / 出款，ton4j） |
| `tgg-app` | 装配与出口（Bot 命令 / Web API / Admin / Security / Mini App 静态页） |

依赖方向：`common → core → federation → escrow → chain → app`。

## 6. 接着读什么（先读顺序，同 `docs/INDEX.md`）

1. `README.md` —— 项目是什么、边界在哪；
2. `docs/CODE-VS-SPEC.md` —— 哪些已落地、落到位没有（**数字以它为准**）；
3. `docs/CONFIG-KEYS.md` —— 配置前必读；
4. `docs/ONLINE-VERIFICATION.md` —— 只能在真机核销的项、怎么核销；
5. `docs/UNDECIDABLE-DECISIONS.md` —— 需要人拍板的商业/语义决策。

## 7. 常见坑（省你十分钟）

- **`license:check` 失败**：新文件缺许可头 → `mvn -B license:format` 一键补齐。
  注意 goal 名：v5 是 `format`（旧文档/旧教程里的 `update-file-header` 是 v4 写法，照抄会报
  "Could not find goal"）。
- **HTTP 代理**：Java 不读 macOS 系统代理，连 `api.telegram.org` 需显式给 JVM 指
  `-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=<端口>`（探测与细节见 `docs/LIVE-TEST-RUNBOOK.md` §1）。
- **JDK 版本切换后必须 `mvn clean`**：跨版本编译产物会残留，症状是「找不到符号」一类假错
  （诊断方法写在 `pom.xml` 的 `java.version` 注释里）。
