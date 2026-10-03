# TON Escrow Toolkit

> **本仓库为单快照版本（2026-10-03）**：包含完整源代码与本说明文档。
> 此前开发过程的提交历史已按仓库所有者要求清除。

一个通用的 **Telegram 链上托管担保交易技术工具包**。只提供技术能力：不提供运营服务、
不含业务参数、不含合规方案，也不针对任何特定运营主体。所有业务参数由部署者自行设定，
并自行承担其后果。

设计原则一句话：**代码回答「能不能做到」，配置回答「允许做到什么」。**

---

## 技术栈

| 层 | 选型 | 备注 |
|---|---|---|
| 语言 | Java 21 | Maven 多模块（6 个） |
| 框架 | Spring Boot **3.5.16（刻意锁定，勿升 4.x）** | Boot 4.x 的 Jackson 3 与 TelegramBots 的 Jackson 2 注解不兼容（Update 反序列化静默失败） |
| Bot | telegrambots-longpolling 10.3.0 | 长轮询 |
| 链 | ton4j 2.1.0 + Tolk 合约（Acton 1.2.0） | ADNL 读写链、TON Connect |
| DB | MySQL 8（Flyway 迁移 V1–V14；测试用 H2） | |
| 前端 | Vue 3 + Vite（`web/`）+ Mini App 静态页 | TON Connect 钱包连接 |

## 模块地图（依赖方向）

```
tgg-common → tgg-core → tgg-federation → tgg-escrow → tgg-chain → tgg-app
```

| 模块 | 职责 |
|---|---|
| `tgg-common` | 异常基类（EscrowException / TggException） |
| `tgg-core` | 领域纯逻辑（准入守卫 / 风控 / 信用判定；无 IO） |
| `tgg-federation` | 联邦签名（Ed25519） |
| `tgg-escrow` | 交易领域 + JPA 持久化 + Flyway 迁移 |
| `tgg-chain` | TON 链交互（钱包 / 部署 / 存证 / 出款 / 状态查询，ton4j） |
| `tgg-app` | 装配与出口（Bot 命令 / Web API / Admin / Security / Mini App） |

其他目录：`contracts/`（Tolk 合约）、`web/`（Vue 前端）、`scripts/`（运维脚本，含 DB 备份）、
`docs/`（设计与核销文档，见下）、`tests/`。

## 构建与测试

```bash
# 全量（单元 + 集成测试；接口/签名变更后必须 clean 重编）
TGG_DB_NAME=tgg_verify TELEGRAM_BOT_TOKEN=123456:dummy-for-it mvn -B clean verify

# 合约
acton test          # contracts/

# 前端
cd web && npm install && npm run build && npm test
```

## 当前完成度（2026-10-03 快照）

- **全量测试绿**：Java 侧 1054 tests / 18 IT；合约侧 `acton test` 62 passed；前端 vitest 全绿。
- **交易主链全栈**：邀请 → 下单/确认 → 托管 → 交付 → 放款/退款 → 争议裁决（联邦签名）→
  信用/榜单 → 费用台账 → TON Pay → 链上（部署/存证/出款/紧急暂停）→ 链上对账回填 →
  Bot 命令面 + Web/Mini App 前端（含资金动作二次确认环）。
- **危险动作安全设计**：资金动作（release/refund）需两次确认（`ConfirmGate` 原子槽 + TTL）；
  Admin 面 Basic 鉴权 fail-closed；链上写操作未接线时返回 503 而非假装成功。
- **文档地图**：`docs/INDEX.md`（读哪份）、`docs/CODE-VS-SPEC.md`（功能落地判定台账）、
  `docs/QUICKSTART.md`（最短启动路径）、`docs/LIVE-TEST-RUNBOOK.md`（真机核销执行序）、
  `docs/ONLINE-VERIFICATION.md`（真机核对表）、`docs/UNDECIDABLE-DECISIONS.md`（待拍板项）、
  `docs/CONFIG-KEYS.md`（配置键权威清单）。

## 安全提示

- 私钥 / 助记词 / Bot token / Admin 凭据**永不入库**——全部走环境变量（见 `docs/CONFIG-KEYS.md`）。
- 部署侧建议 Nginx `location ^~ /admin/ { deny all; }` 作为应用层鉴权之外的第一道门。

## 许可证

**AGPL-3.0-only**——见 `LICENSE`（合约目录 `contracts/` 同许可）。
