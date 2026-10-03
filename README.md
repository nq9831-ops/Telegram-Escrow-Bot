# TON Escrow Toolkit

**Telegram 链上托管担保交易工具包**——Bot 命令 + TON 托管合约 + Web/Mini App 前端。
只提供技术能力（不含业务参数与合规方案）；费率、限额等业务数值由部署者自行配置。

---

## 有什么功能

- **担保交易全流程**：发单 → 预览确认 → 锁仓 → 交付 → 放款 / 退款（支持邀请链接一键接单、回复消息免输 ID 下单）
- **争议与裁决**：发起争议 → 双方陈述 / 确认已读 → 证据留存（加盐哈希）→ 联邦签名裁决
- **资金动作防误触**：放款 / 退款需**二次确认**（发 `--yes` 或点确认按钮，5 分钟内有效）
- **信用与榜单**：成交信用分（五维拆解）、交易榜（默认匿名，可 opt-in 公开用户名）
- **费用**：平台费率可配置（默认 0%，支持首单豁免）；费用台账可查
- **链上**：TON 托管合约（Tolk）、部署、存证、出款、紧急暂停（仅联邦）、链上对账回填
- **TON Pay**：支付登记 + webhook 结算（HMAC 验签，fail-closed）
- **Web / Mini App**：交易表单与订单页（TON Connect 钱包连接与签名）
- **管理面**：Admin API（HTTP Basic 鉴权）——链地址推导 / 费用台账 / 裁决 / 对账 / 暂停

## 如何部署

**1. 构建**

```bash
mvn -B clean package                      # 产出 tgg-app/target/tgg-app-0.1.0-SNAPSHOT.jar
cd web && npm install && npm run build    # 前端产物（可选）
```

**2. 准备**

- JDK 21、MySQL 8（建一个空库，启动时 Flyway 自动建表）
- Telegram Bot Token（找 @BotFather 申请）+ Bot 用户名
- 环境变量：

```bash
export TELEGRAM_BOT_TOKEN=123456:xxxx     # 必配
export TELEGRAM_BOT_USERNAME=your_bot     # 必配（不带 @）
export TGG_DB_URL=jdbc:mysql://127.0.0.1:3306/tgg
export TGG_DB_USERNAME=tgg
export TGG_DB_PASSWORD=******
# 其余全部配置键（含默认值）见 docs/CONFIG-KEYS.md——未配置的项按默认值或不启用
```

**3. 启动**

```bash
java -jar tgg-app/target/tgg-app-0.1.0-SNAPSHOT.jar
```

接口层默认 `:8080`；Mini App 静态页在 `/miniapp/index.html`。

**4. 上真机与链上**（合约部署 / 钱包注入 / Telegram 内全流程走通）

按 `docs/LIVE-TEST-RUNBOOK.md` 的六线执行序操作（本地预检 → 合约部署 → 资金链 → 治理 → 命令面 → 配置注入）。升级前先备份数据库——新版迁移会删除废弃表（`scripts/backup-db.sh`）。

## 如何使用

### Telegram Bot

```
/start                                  打开交易表单（Mini App）
/escrow invite <金额> <币种>            生成邀请链接（对方点开即接单）
/escrow create <卖方ID> <金额> <币种>   发单预览 → 再发 confirm 确认成交
/escrow lock <订单号>                   买方锁仓托管
/escrow deliver <订单号>                卖方交付
/escrow release <订单号>                买方验收放款（需二次确认）
/escrow refund <订单号> [理由]          退款（需二次确认）
/escrow dispute <订单号> <理由>         发起争议（配套 statement / read / evidence）
/escrow status <订单号>                 查状态与下一步（回执带动作按钮）
/escrow my                              查看我参与的交易（不必记订单号）
/escrow rank / credit / publish         交易榜 / 我的信用 / 榜单公开开关
/help                                   全部命令
```

小技巧：在群里**回复对方的消息**再发 `/escrow create <金额> <币种>`，免输卖方 ID。

### Web / Mini App

浏览器或 Telegram 内打开站点 → 「发起担保」填信息并连接钱包（TON Connect）签名下单；
「订单状态」输入订单号查询，资金类动作（放款 / 退款等）经钱包签名执行（页面亦有防误触确认）。

## 文档导航

| 文档 | 用途 |
|---|---|
| `docs/QUICKSTART.md` | 最短启动路径（本地跑起来） |
| `docs/CONFIG-KEYS.md` | **全部配置键**权威清单（默认值 / 行为） |
| `docs/LIVE-TEST-RUNBOOK.md` | 真机核销执行手册（部署到链上全流程） |
| `docs/CODE-VS-SPEC.md` | 功能落地判定台账（哪些已实现 / 部分实现） |
| `docs/ONLINE-VERIFICATION.md` | 真机核对表 |
| `docs/UNDECIDABLE-DECISIONS.md` | 待拍板项 |
| `docs/INDEX.md` | 文档地图（该读哪份、该信哪份） |
| `PROJECT-SNAPSHOT.md` | 快照说明（技术栈 / 结构 / 完成度） |

## 常见问题

- **`license:check` 失败**：新文件缺许可头 → `mvn -B license:format` 一键补齐。
- **改接口后构建"假成功"**：必须 `mvn -B clean verify`（增量编译会混用旧 class）。
- **HTTP 代理**：Java 不读系统代理，连 Telegram 需 `-Dhttps.proxyHost=... -Dhttps.proxyPort=...`。
- **测试命令**：`TGG_DB_NAME=tgg_verify TELEGRAM_BOT_TOKEN=123456:dummy-for-it mvn -B clean verify`（token 值可假，仅需非空）。

## 许可

**AGPL-3.0-only**——见 `LICENSE`。
