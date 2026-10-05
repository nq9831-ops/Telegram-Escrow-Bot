#!/usr/bin/env bash
# ============================================================================
# tgg-escrow 环境变量模板
# ----------------------------------------------------------------------------
# 用法：复制为 env.sh → 填入真实值 → chmod 600 env.sh → 不要提交进任何版本库。
# 格式：纯 KEY=VALUE（会被 run-app.sh 以 set -a 方式 source 进 java 进程环境）。
# 标注：[必填]=缺了无法正确运行；[按需]=功能开关，不填=该功能关闭或有合理默认；
#       [调优]=有默认值，按运营需要调整。
# 时间值格式：ISO-8601 时长（如 PT24H=24 小时、PT5M=5 分钟、PT0S=0 秒）。
# ============================================================================
set -a

# ── 数据库（必填；MySQL 8）────────────────────────────────────────────────────
TGG_DB_HOST=127.0.0.1
TGG_DB_PORT=3306
TGG_DB_NAME=tgg_escrow
TGG_DB_USER=tgg
TGG_DB_PASSWORD=

# ── Telegram Bot（必填）──────────────────────────────────────────────────────
# Bot Token 只从环境变量读取（绝不写进任何配置文件）。
TELEGRAM_BOT_TOKEN=
# Bot 用户名（不带 @）——用于生成深链与邀请链接。
TELEGRAM_BOT_USERNAME=

# ── Mini App / Web（必填：必须是 HTTPS 公网地址，Telegram 硬性要求）───────────
TGG_WEBAPP_URL=https://your-domain.example
TGG_WEBAPP_INITDATA_MAX_AGE=PT1H            # [调优] initData 验签最大时延（防重放）

# ── 管理端点（必填；Basic 认证账号，保护 /admin/*）────────────────────────────
TGG_ADMIN_USERNAME=
TGG_ADMIN_PASSWORD=

# ── TON 链（必填组）──────────────────────────────────────────────────────────
TGG_CHAIN_TESTNET=false                     # ⚠ 默认 true（测试网）！上主网必须显式 false
TGG_CHAIN_JETTON_MASTER=                    # USDT 等 jetton 的 master 地址（用 USDT 结算时必填）
TGG_CHAIN_UPGRADE_WALLET_MNEMONIC=          # 联邦/升级钱包助记词（24 词）。安全红线见部署教程 §12
TGG_CHAIN_UPGRADE_WALLET_ID=2147483645      # walletId：testnet 默认 2147483645 / mainnet 默认 2147483409（用 scripts/wallet-selfcheck.java 核验，勿拍脑袋填）
TGG_FEDERATION_ADDRESS=                     # 联邦钱包地址（= 升级钱包地址；合约以此校验治理签名）
TGG_FEDERATION_PUBLIC_KEY=                  # 联邦公钥（base64）
TGG_VERDICT_BUYER_PUBLIC_KEY=               # 裁决三方签名：买方公钥
TGG_VERDICT_SELLER_PUBLIC_KEY=              # 裁决三方签名：卖方公钥

# ── 治理冷/热分离（[按需]；不填=auto，共用热身份）────────────────────────────
TGG_CHAIN_GOVERNANCE_MODE=auto              # auto | offline（offline=治理签名交冷身份）
TGG_CHAIN_GOVERNANCE_PUBLIC_KEY=            # 冷身份公钥（base64；offline 模式必填）
TGG_CHAIN_GOVERNANCE_WALLET_ID=2147483645

# ── 交易参数（[调优]）────────────────────────────────────────────────────────
TGG_ALLOWED_CURRENCIES=                     # 允许的币种（如 TON,USDT）；留空=应用内默认
TGG_TRADE_CONCURRENT_MAX=1                  # 单用户并发在途交易上限
TGG_TRADE_COOLDOWN=PT0S                     # 两笔交易之间的冷却
TGG_CONFIRM_WINDOW=PT5M                     # create→confirm 二次确认窗口
TGG_PENDING_TTL=PT10M                       # 待接受邀请的有效期
TGG_INVITE_TTL=PT24H                        # 邀请链接有效期
TGG_TRADE_GROUP_SILENCE_DAYS=7              # 交易群静默期（天）
TGG_TIER_NORMAL_MAX=100                     # 信用分层：普通上限
TGG_TIER_CONFIRM_MAX=1000                   # 信用分层：需二次确认上限

# ── 费用（[调优]；默认全 0=免费）─────────────────────────────────────────────
TGG_FEE_PLATFORM_RATE=0                     # 平台费率（bps 或百分比口径见应用内定义）
TGG_FEE_ARBITRATION_RATE=0                  # 裁决费
TGG_FEE_FEDERATION_SHARE=0                  # 联邦分成
TGG_FEE_FIRST_ORDER_FREE=true               # 首单免费
TGG_CHAIN_FEE_ADDRESS=                      # 链上扣费收款地址（方案 B；留空=链上不扣费）
TGG_CHAIN_FEE_RATE_BPS=                     # 链上费率（bps）

# ── 争议（[按需]）────────────────────────────────────────────────────────────
TGG_DISPUTE_EVIDENCE_WINDOW=PT24H           # 证据提交窗口
TGG_DISPUTE_REQUIRE_STATEMENTS=false        # 是否要求双方先陈述才可裁决

# ── 信用 / 榜单（[按需]）─────────────────────────────────────────────────────
TGG_CREDIT_WEIGHTS=                         # 信用权重表（留空=不启用信用评分）
TGG_CREDIT_DECAY=                           # 信用衰减
TGG_CREDIT_WEEKLY_PUSH=true                 # 周榜推送
TGG_CREDIT_WEEKLY_PUSH_SIZE=10              # 周榜条数

# ── 运营台登录（[按需]；一期钱包登录，ton_proof）─────────────────────────────
# 留空=功能关闭（fail-closed）：密钥空 → 登录端点 503；白名单空 → 无人可登录。
TGG_OPS_SESSION_SECRET=                     # 会话签发密钥（生成：openssl rand -base64 48；勿入库）
TGG_OPS_ADMIN_WALLETS=                      # 运营者钱包白名单（raw 形态 0:<hex>，逗号分隔，大小写不敏感）

# ── 通知（[按需]）────────────────────────────────────────────────────────────
TGG_NOTIFY_SILENCE_START=                   # 静默时段起点（如 22:00；留空=不启用）
TGG_NOTIFY_SILENCE_END=                     # 静默时段终点（如 02:00；支持跨午夜）
TGG_NOTIFY_SILENCE_ZONE=UTC                 # 静默时段时区

# ── 日志（[按需]）────────────────────────────────────────────────────────────
TGG_LOG_SALT=                               # 用户 ID 日志脱敏盐（留空=进程级随机盐）

# ── 心跳脚本（服务器侧用；由 heartbeat.sh 消费，非应用读取）───────────────────
TGG_HEARTBEAT_URL=http://127.0.0.1:8080
TGG_ADMIN_USER=${TGG_ADMIN_USERNAME}        # 脚本双轨命名：从上面映射
TGG_ADMIN_PASS=${TGG_ADMIN_PASSWORD}

set +a
