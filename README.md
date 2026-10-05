# tgg-escrow 部署包

这是「Telegram 担保交易系统」的完整部署包：能直接跑的程序、合约源码、运维脚本、配置模板，
外加几份说明书。

> 本包构建于 2026-10-05。想确认程序没被掉包：算一下 `app/tgg-app.jar` 的 sha256，
> 应该等于 `0a57d693d026f40a76364a37e6767a835d9aff5f4a108eadce5bac988769b634`；
> 合约代码哈希在 `contracts/escrow-resources/EscrowContract.hash`（AE212B27 开头）。

## 包里都有啥

```
tgg-escrow-deploy/
├── README.md                     ← 你正在看的这份
├── docs/
│   ├── 00-手把手部署-大白话版.md   ← 第一次部署，从这份开始：每步都写清楚点哪里、填什么、看到什么算成功
│   ├── 01-部署教程.md             ← 详细版教程，主要用来查细节和排错；systemd 方式在附录 A
│   └── 02-使用说明.md             ← 系统装好之后怎么用：机器人命令、Mini App、管理后台、治理操作
├── app/
│   └── tgg-app.jar               ← 程序本体（Java 21 直接跑）
├── contracts/
│   ├── EscrowContract.tolk       ← 托管合约源码
│   ├── types.tolk                ← 合约里的类型定义
│   ├── EscrowContract.test.tolk  ← 合约的测试
│   └── escrow-resources/
│       ├── EscrowContract.code.b64   ← 合约编译产物（程序里内置的是同一份）
│       └── EscrowContract.hash       ← 合约代码哈希，用来核对
├── scripts/
│   ├── heartbeat.sh              ← 心跳脚本：让面板的计划任务定时调它
│   ├── backup-db.sh              ← 数据库备份脚本
│   └── wallet-selfcheck.java     ← 钱包自检小工具：从助记词算出地址/公钥，跟程序里显示的自动对一遍
└── config/
    ├── env.example.sh            ← 环境变量模板，复制成 env.sh 往里填
    ├── run-app.sh                ← 启动脚本模板（systemd 方式用，见教程附录 A）
    └── tgg-bot.service           ← systemd 服务模板（同上，附录 A）
```

## 部署速查（10 步）

第一次装，别用这份速查——直接看 `docs/00-手把手部署-大白话版.md`，那份写得最细。
下面是给熟手看的路标；每一步的完整解释和排错都在 `docs/01-部署教程.md`。

1. 服务器装好**宝塔面板**；软件商店里装 Nginx、MySQL 8、Java 项目管理器，JDK 装 21
2. 面板 → 数据库：建库 `tgg_escrow`（用户 `tgg`，访问权限选 127.0.0.1），记下密码
3. 找 @BotFather 申请机器人，拿 Token 和机器人用户名；把命令表贴进去（`/setcommands`，内容见教程）
4. 准备 TON 钱包：管升级的钱包（V5R1）助记词和公钥；收 USDT 再备一个 jetton 主合约地址
5. 面板 → 文件：把 `app/tgg-app.jar` 和填好的 `env.sh` 传到 `/www/wwwroot/tgg/`，`env.sh` 权限设 600
6. 面板 → 网站 → Java 项目 → 添加：jar 选上面那个，JDK 选 21，端口 8080；
   启动命令用教程 6.3 节那种自定义写法（先 `source env.sh`，再 `exec java`）
7. 给项目绑域名，再加一条反向代理指向 `http://127.0.0.1:8080`。
   **两步都要做，只绑域名不加反代会 403**。然后用面板的 SSL 申请证书，开强制 HTTPS
8. 验证：项目状态「运行中」、日志里出现 `Started EscrowBotApplication`、教程 7.3 节的端点检查全过
9. 面板 → 计划任务：加心跳任务（每 10 分钟一次，命令在教程 8.1 节，先手动跑一遍看看结果）；
   再加一个数据库备份（每天一次）
10. Telegram 里冒烟（`/start`、`/escrow`、Mini App 各点一遍），然后照教程第 9 章的清单，
    在测试网完整走一单交易，没问题再切主网

## 安全上的几件事（动手前先看）

- `env.sh` 里有机器人 Token 和钱包助记词。权限设 600，别发给任何人，更别提交到代码仓库。
  往面板填启动命令时只写文件路径，密钥不要打字进面板。
- **合约不用你手动部署**。每笔订单的托管合约，是用户用自己的钱包签名后、程序自动部署的。
  那个「治理钱包」只管升级合约用（提案、应用，72 小时公示期加逃生票），日常交易用不到它。
- 宝塔面板本身也要加固：改默认端口、开面板 SSL、限制登录 IP（教程第 12 章）。
- 想核对合约代码有没有被动过，看 `contracts/escrow-resources/EscrowContract.hash`。

## 版本与许可

- 应用版本：`0.1.0-SNAPSHOT`（本包打包的就是这个版本）
- 部署方式以**宝塔面板**为主线（文档按最新面板流程写）；`config/` 里的 systemd 模板是备选方式
- 许可：AGPL-3.0-only——使用本包即受其约束
