# 配置键权威清单（CONFIG-KEYS）

> **地位**：部署者/运营者唯一权威配置参考。**由命令实测生成**——
> `grep -rhoE '\$\{tgg\.[a-z0-9.-]+' --include='*.java' tgg-*/src/main | sort -u`
> **本清单登记 70 键**（含群管理整体移除后仅存于清单的迁移期键，见下方 ⚠️ 范围变更）；
> **代码在用活键实测 42 条**（文末复算命令给出；本清单创建时该值为 67，群管理移除后下降——两组数字不同属正常）。
> 默认值取自代码 `@Value` 注解。README §6 的「规划意图」表**不是**本清单，勿按它配置。
> 新增键必须同步本文件 + README §6 指向（+ 默认行为测试）——改完重跑文末命令对账键数。
>
> **环境变量**：tgg.a.b-c → TGG_A_B_C（点/连字符 → 下划线，全大写）。
> **空串语义**：默认值为 `(空)` 的键，留空即「未配置」，对应能力**不启用**（fail-closed 或取进程内默认）；
> 这是本项目惯例（与「留空=默认」先例一致），**不是抛错**。另有少量非法值在启动期 fail-fast。
>
> ⚠️ **2026-10-03 范围变更**：群管理已整体移除——`tgg.guard.* / tgg.join.* / tgg.warning.* /
> `tgg.welcome.* / tgg.risk.* / tgg.protection.* / tgg.moderation.*` 键**已无代码消费者**
> （本清单保留供迁移期参考；深度清理批次待续，见 HANDOFF 第 17 条）。

## 1. 必备键（无默认值）

| 键 | 环境变量 | 默认 | 说明 |
|---|---|---|---|
| `tgg.bot.username` | `TELEGRAM_BOT_USERNAME`（历史命名；或 `TGG_BOT_USERNAME` 直绑，两者均实测可用） | （必配；未配置=启动失败） | Bot 用户名（命令解析与深链用）。未配置时启动直接失败（fail-fast，错误信息点名本键），不会静默拼出指向别的 bot 的深链；与 token 不对应会导致命令带不上 bot 名 |

## 2. 管理凭据与安全

| 键 | 环境变量 | 默认 | 说明 |
|---|---|---|---|
| `tgg.admin.username` | `TGG_ADMIN_USERNAME` | (空) | Admin API HTTP Basic 用户名；未配=全部 `/admin/**` 401（fail-closed） |
| `tgg.admin.password` | `TGG_ADMIN_PASSWORD` | (空) | Admin API 密码；同上 |
| `tgg.log.salt` | `TGG_LOG_SALT` | (空) | 日志脱敏盐（用户 ID 掩码）；未配=进程级随机盐（重启后掩码变化，启动有 warn） |
| `tgg.webapp.initdata.max-age` | `TGG_WEBAPP_INITDATA_MAX_AGE` | PT1H | Mini App initData 验签的最大年龄（防重放） |

## 3. 交易与费用

| 键 | 环境变量 | 默认 | 说明 |
|---|---|---|---|
| `tgg.fee.platform-rate` | `TGG_FEE_PLATFORM_RATE` | 0 | 平台费率（放款时扣除）；0=不收费 |
| `tgg.fee.arbitration-rate` | `TGG_FEE_ARBITRATION_RATE` | 0 | 仲裁费率（仅败诉产生） |
| `tgg.fee.federation-share` | `TGG_FEE_FEDERATION_SHARE` | 0 | 联邦管理费从仲裁费的分成比例 |
| `tgg.fee.first-order-free` | `TGG_FEE_FIRST_ORDER_FREE` | true | 首单平台费豁免（卖方首笔成功交易免平台费，只作用于平台费） |
| `tgg.allowed-currencies` | `TGG_ALLOWED_CURRENCIES` | (空) | 允许受理的币种（逗号分隔，如 `USDT`）；留空=不收窄（TON+USDT）；非法值启动期抛 |
| `tgg.trade.concurrent-max` | `TGG_TRADE_CONCURRENT_MAX` | 1 | 同一用户并发交易上限 |
| `tgg.trade.cooldown` | `TGG_TRADE_COOLDOWN` | PT0S | 交易完成后的冷却期（0=无） |
| `tgg.trade.tier.normal-max` | `TGG_TRADE_TIER_NORMAL_MAX` | 100 | 普通档单笔金额上限 |
| `tgg.trade.tier.confirm-max` | `TGG_TRADE_TIER_CONFIRM_MAX` | 1000 | 确认档单笔金额上限 |
| `tgg.invite.ttl` | `TGG_INVITE_TTL` | PT24H | 深链邀请的有效期 |
| `tgg.pending.ttl` | `TGG_PENDING_TTL` | PT10M | 建单预览（create→confirm 两步流）的登记有效期 |
| `tgg.confirm.window` | `TGG_CONFIRM_WINDOW` | PT5M | **动作确认槽**（release/refund 二次确认）的 TTL：用户「发起动作 → 点确认」之间槽位的存活时长，过期即失效、不再放行。**与 `tgg.pending.ttl` 语义不同**——后者管「create→confirm 建单预览」的登记存活（两步建单流），本键管「已发起的危险动作待确认」的存活（两步确认流）；两者不可混用，误配会导致确认窗口与建单窗口相互覆盖。代码消费者：`ConfirmGate`（tgg-escrow 纯逻辑；随本波 be-confirm 分片落地——本清单先行登记） |
| `tgg.fraud.concentration-threshold` | `TGG_FRAUD_CONCENTRATION_THRESHOLD` | 0.6 | 对手集中度阈值（互刷判定与信用剔除共用） |

## 4. 信用与榜单

| 键 | 环境变量 | 默认 | 说明 |
|---|---|---|---|
| `tgg.credit.weights` | `TGG_CREDIT_WEIGHTS` | (空) | 五维权重（`完成数,争议率,好评率,集中度,活跃`）；留空=代码内默认 30/25/20/15/10；构造期校验 5 项+总和=1 |
| `tgg.credit.decay` | `TGG_CREDIT_DECAY` | (空) | 活跃时长衰减参数（`宽限,步长,因子,下限`）；留空=不衰减 |
| `tgg.credit.weekly-push` | `TGG_CREDIT_WEEKLY_PUSH` | true | 周榜惰性推送开关（群内交互触发、每周每群至多一条） |
| `tgg.credit.weekly-push-size` | `TGG_CREDIT_WEEKLY_PUSH_SIZE` | 10 | 周榜推送条数 |

## 5. 争议与证据

| 键 | 环境变量 | 默认 | 说明 |
|---|---|---|---|
| `tgg.dispute.evidence-window` | `TGG_DISPUTE_EVIDENCE_WINDOW` | PT24H | 证据提交时效窗口（自争议进入时刻起算） |
| `tgg.dispute.require-statements` | `TGG_DISPUTE_REQUIRE_STATEMENTS` | false | 裁决前置门：true=双方陈述齐备才可 `/admin/verdict` |

## 6. 联邦与链上（TON）

| 键 | 环境变量 | 默认 | 说明 |
|---|---|---|---|
| `tgg.federation.public-key` | `TGG_FEDERATION_PUBLIC_KEY` | (空) | 联邦公钥；未配=裁决验签 fail-closed 拒一切 |
| `tgg.chain.testnet` | `TGG_CHAIN_TESTNET` | true | 链网络（须与 jetton master / 合约部署网络三方对齐，错配=静默收不到入金） |
| `tgg.chain.jetton-master` | `TGG_CHAIN_JETTON_MASTER` | (空) | Jetton master 地址；未配=链上能力关闭（`/admin/chain` 返 503） |
| `tgg.chain.upgrade.wallet-mnemonic` | `TGG_CHAIN_UPGRADE_WALLET_MNEMONIC` | (空) | 联邦钱包助记词（与 seed 二选一；都配=启动失败） |
| `tgg.chain.upgrade.wallet-seed-base64` | `TGG_CHAIN_UPGRADE_WALLET_SEED` | (空) | 联邦钱包 32 字节 seed 的 Base64（与助记词二选一；都不配=写链栈未接线 fail-closed） |
| `tgg.chain.upgrade.wallet-id` | `TGG_CHAIN_UPGRADE_WALLET_ID` | 2147483645 | 钱包 id（v5r1 testnet/subwallet0 标准值；误配=钱包拒收全部签名，核销前须地址自检） |

## 7. 消息守卫（内容安全与限流）

| 键 | 环境变量 | 默认 | 说明 |
|---|---|---|---|
| `tgg.guard.allowed-domains` | `TGG_GUARD_ALLOWED_DOMAINS` | (空) | 链接白名单域名；留空=不启用链接过滤（保护性缺省：宁缺勿滥删） |
| `tgg.guard.allowed-channels` | `TGG_GUARD_ALLOWED_CHANNELS` | 未配置 | 频道傀儡防御白名单；未配置=不参与判定；配成空串=全拦频道帖 |
| `tgg.guard.allowed-extensions` | `TGG_GUARD_ALLOWED_EXTENSIONS` | jpg,jpeg,png,gif,pdf,zip,doc,docx,xls,xlsx,pptx | 媒体文件扩展名白名单 |
| `tgg.guard.allowed-mime-types` | `TGG_GUARD_ALLOWED_MIME_TYPES` | image/jpeg,image/png,image/gif,application/pdf | 媒体 MIME 白名单 |
| `tgg.guard.shortener-domains` | `TGG_GUARD_SHORTENER_DOMAINS` | t.cn,bit.ly,tinyurl.com | 短链域名表（短链强制展开再判） |
| `tgg.guard.rate.window` | `TGG_GUARD_RATE_WINDOW` | PT10S | 限流窗口 |
| `tgg.guard.rate.user` | `TGG_GUARD_RATE_USER` | 5 | 用户级限流（窗口内消息数） |
| `tgg.guard.rate.group` | `TGG_GUARD_RATE_GROUP` | 20 | 群级限流 |
| `tgg.guard.rate.global` | `TGG_GUARD_RATE_GLOBAL` | 100 | 全局限流 |
| `tgg.guard.repeat.threshold` | `TGG_GUARD_REPEAT_THRESHOLD` | 0 | 重复消息判定阈值；**0=不启用** |
| `tgg.guard.repeat.window` | `TGG_GUARD_REPEAT_WINDOW` | PT1M | 重复检测窗口 |

## 8. 入群风控与保护

| 键 | 环境变量 | 默认 | 说明 |
|---|---|---|---|
| `tgg.join.required-channels` | `TGG_JOIN_REQUIRED_CHANNELS` | (空) | 入群订阅门禁（须落在 allowed 白名单内）；留空=不启用 |
| `tgg.join.allowed-channels` | `TGG_JOIN_ALLOWED_CHANNELS` | (空) | 订阅校验的可选频道集 |
| `tgg.risk.weights` | `TGG_RISK_WEIGHTS` | (空) | 入群风险权重表（如 `NO_USERNAME=30`）；留空=不启用评分；未知因子/非法分值启动期抛 |
| `tgg.risk.reject-threshold` | `TGG_RISK_REJECT_THRESHOLD` | 50 | 风险分 ≥ 此值 → 拒绝入群 |
| `tgg.risk.review-threshold` | `TGG_RISK_REVIEW_THRESHOLD` | 20 | 风险分 ≥ 此值（低于 reject）→ 留痕复核 |
| `tgg.risk.review-notify-chat` | `TGG_RISK_REVIEW_NOTIFY_CHAT` | (空) | REVIEW 档管理员通知会话 ID；留空=只留痕不通知；非法值启动期抛 |
| `tgg.risk.new-account-days` | `TGG_RISK_NEW_ACCOUNT_DAYS` | 7 | 「新账号」天数判据 |
| `tgg.risk.allowed-languages` | `TGG_RISK_ALLOWED_LANGUAGES` | (空) | 允许语言列表；留空=不限 |
| `tgg.protection.enabled` | `TGG_PROTECTION_ENABLED` | false | 保护模式总开关 |
| `tgg.protection.max-joins` | `TGG_PROTECTION_MAX_JOINS` | 0 | 窗口内入群上限；**0=不检测** |
| `tgg.protection.window` | `TGG_PROTECTION_WINDOW` | PT1M | 入群爆发判定窗口 |
| `tgg.protection.cooldown` | `TGG_PROTECTION_COOLDOWN` | PT10M | 保护模式冷却期（期满惰性解除） |

## 9. 维护期与通知

| 键 | 环境变量 | 默认 | 说明 |
|---|---|---|---|
| `tgg.maintenance.option1` | `TGG_MAINTENANCE_OPTION1` | PT1H | 维护期可选时长档位 1 |
| `tgg.maintenance.option2` | `TGG_MAINTENANCE_OPTION2` | PT6H | 维护期可选时长档位 2 |
| `tgg.maintenance.option3` | `TGG_MAINTENANCE_OPTION3` | PT24H | 维护期可选时长档位 3 |
| `tgg.maintenance.option4` | `TGG_MAINTENANCE_OPTION4` | PT72H | 维护期可选时长档位 4 |
| `tgg.maintenance.option5` | `TGG_MAINTENANCE_OPTION5` | PT168H | 维护期可选时长档位 5 |
| `tgg.maintenance.default-index` | `TGG_MAINTENANCE_DEFAULT_INDEX` | 2 | 默认选中的维护期档位（1-5） |
| `tgg.notify.silence-start` | `TGG_NOTIFY_SILENCE_START` | (空) | 通知静默窗口起点（HH:mm） |
| `tgg.notify.silence-end` | `TGG_NOTIFY_SILENCE_END` | (空) | 通知静默窗口终点 |
| `tgg.notify.silence-zone` | `TGG_NOTIFY_SILENCE_ZONE` | UTC | 静默窗口时区 |

## 10. 交易群 / 警告 / 欢迎 / Webapp

| 键 | 环境变量 | 默认 | 说明 |
|---|---|---|---|
| `tgg.trade-group.silence-days` | `TGG_TRADE_GROUP_SILENCE_DAYS` | 7 | 交易群静默期天数（≥1；0 会使启动失败） |
| `tgg.warning.mute-threshold` | `TGG_WARNING_MUTE_THRESHOLD` | 3 | 累计警告达此值 → 禁言 |
| `tgg.warning.kick-threshold` | `TGG_WARNING_KICK_THRESHOLD` | 5 | 累计警告达此值 → 踢出 |
| `tgg.warning.default-mute` | `TGG_WARNING_DEFAULT_MUTE` | PT10M | 触线禁言的默认时长 |
| `tgg.moderation.log-channel-id` | `TGG_MODERATION_LOG_CHANNEL_ID` | 0 | 管理留痕频道（GM-20）：已执行的群管理动作推到该私密频道；**0=不启用**（留痕需显式配置频道） |
| `tgg.tonpay.api-secret` | `TONPAY_API_SECRET` | (空) | TON Pay webhook 验签密钥（ET-32）；**空=TON Pay 未启用**（webhook 端点回 503，fail-closed）。服务端密钥——禁入库、环境变量注入 |
| `tgg.welcome.template` | `TGG_WELCOME_TEMPLATE` | 欢迎 {username}… | 新成员欢迎语模板（占位符 {username}） |
| `tgg.webapp.url` | `TGG_WEBAPP_URL` | (空) | Mini App 公开 URL（HTTPS 隧道地址；TON Connect 深链用） |

## 复算命令

```bash
# 键数与本清单对账（活键口径——当前期望 42；与头部「登记 70 键」不同属正常：清单保留了迁移期死键）
grep -rhoE '\$\{tgg\.[a-z0-9.-]+' --include='*.java' tgg-*/src/main | sed 's/\${//' | sort -u | wc -l
# 某键默认值实测
grep -rhoE '\$\{tgg.KEY[^}]*\}' --include='*.java' tgg-*/src/main | head -1
```

> 已知边界：默认值取自 `@Value` 静态文本；若代码改用 `@ConfigurationProperties`/SpEL，此清单须同步更新。
> 另有非 `tgg.*` 前缀的运行参数（如 `TELEGRAM_BOT_TOKEN`、`TELEGRAM_BOT_USERNAME`、`TGG_DB_NAME`、`SERVER_PORT`）不在此表，
> 见 `docs/QUICKSTART.md` §4 与 `docs/LIVE-TEST-RUNBOOK.md`。（本仓文档中出现的 `.rivet/HANDOFF.md` 是本地交接文件、不入库，勿作为公开引用。）
