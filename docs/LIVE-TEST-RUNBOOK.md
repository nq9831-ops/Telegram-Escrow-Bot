# 真机测试操作手册（testnet 部署 · 核销执行序）

> **分工**：`ONLINE-VERIFICATION.md` 是**状态表**（每项核销现状）；本文是**执行序列**（部署当天按顺序跑什么、每步看什么）。
> 每跑完一项 → 回写对应表的 `☐ → ✅`（附证据：哈希/截图/读数）——**不跑不算数**。

## 总览：覆盖地图与执行顺序

| 线 | 内容 | 详述位置 |
|---|---|---|
| **① 本地预检** | `mvn clean verify` / `acton build && acton test`（62 用例）/ `cd web && npm run build && npm test`（5 用例）/ 备份脚本演练 | 各命令的输出即证据；备份见 `scripts/backup-db.sh` |
| **② 合约部署（testnet）** | B10 流程（**第 0 步钱包地址预检** → 部署 → 核对） | ONLINE-VERIFICATION **B10** 行（步骤清单在表内） |
| **③ 资金链全流程** | 部署 → 入金 → 交付 → 确认（+ 退款/争议加演）——**用户自己的钱包** | **本文第 0–6 节（B13 专线，详述）** |
| **④ 合约治理核销** | B14（紧急暂停）/ B15（读面 + 对账回填） | **本文第 7 节** |
| **⑤ Bot 命令面冒烟** | A 系列（A1–A19 旧批 + **A20–A23 新批**） | **本文第 8 节（新批）**；旧批在 ONLINE-VERIFICATION A 表逐项 |
| **⑥ 配置注入核销** | C9（新配置项线上生效）/ C10（币种白名单）/ C11（频道门禁） | ONLINE-VERIFICATION C 表；配置清单 `docs/CONFIG-KEYS.md` |

**建议顺序**：① → ② → ③ → ④ → ⑤ → ⑥（后三步建立在 ②③ 的部署与订单之上——同一批订单可连续核销）。

---

## 0. 前置清单（做一次）

| 需要什么 | 怎么搞 |
|---|---|
| **两个 Telegram 账号**（买方 / 卖方） | 自交易会被拒（`SELF_ACCEPT`），必须两个账号 |
| **两个 Tonkeeper testnet 钱包** | Tonkeeper 里 Settings → 添加钱包 → 新建（**两个都切到 Testnet 网络**）。记下两个地址（下面的「买方钱包 A」「卖方钱包 B」） |
| **两个钱包各有 ≥1.5 GRAM（testnet）** | 各自在 `@testgiver_ton_bot` 领（Get 2 GRAM，贴地址；细节见 HANDOFF） |
| **联邦钱包有余额** | `tgg-federation`（`kQCGdtFAuPCpAVsVdguEHRnShZnyslchRMYDLuWP1BU5RqhV`）——当前 ~3 GRAM，够部署 0.3 + 若干消息 |
| **cloudflared（HTTPS 隧道）** | `brew install cloudflared`（Telegram 的 Mini App 按钮**只认 HTTPS**，本地 http 不行） |
| Bot token / 管理凭据 | 你已有的 `TELEGRAM_BOT_TOKEN`；admin 基本认证 `TGG_ADMIN_USERNAME` / `TGG_ADMIN_PASSWORD`（自己设） |

---

## 1. 起服务（本地）

> **代理必读（2026-10-02 实测）**：本机直连 `api.telegram.org` 不通，需挂代理（如 127.0.0.1:10808）。
> **Java 不读 macOS 系统代理**——必须给 JVM 显式指：`-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=<端口>`（HTTP 同理加 `-Dhttp.*`）。
> 探测代理端口：`scutil --proxy | grep -E "HTTPProxy|HTTPSPort"`；验证通路：`curl -x http://127.0.0.1:<端口> https://api.telegram.org/`。
> 链上 ADNL 是裸 TCP 不走 HTTP 代理，不受影响。

```bash
cd "/Users/yishenghe/Downloads/telegram担保系统"

# 先给隧道留出域名占位——先跑第 2 步拿到隧道 URL 再回来填 TGG_WEBAPP_URL
source env.local   # 内含 TGG_CHAIN_UPGRADE_WALLET_MNEMONIC 与 TELEGRAM_BOT_TOKEN
TGG_DB_NAME=tgg_escrow_verify \
TGG_ADMIN_USERNAME=admin TGG_ADMIN_PASSWORD=<自设> \
TGG_CHAIN_TESTNET=true \
TGG_WEBAPP_URL=https://<你的隧道域名> \
SERVER_PORT=8081 \
java -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=<代理端口> \
     -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=<代理端口> \
     -jar tgg-app/target/tgg-app-0.1.0-SNAPSHOT.jar
```

- 打包：`mvn -B -pl tgg-app -am package -DskipTests`（fat jar 在 `tgg-app/target/`）。
- 注意 8080 若被 nginx 占用，用 `SERVER_PORT=8081`。
- 数据库用本机 MySQL；`tgg_escrow_verify` 是验证库，Flyway 自动迁到位。
- **钱包自检（建议）**：`./s5-check.sh`（读 env.local → 推导地址 → 与 acton 对拍）。

## 2. 开 HTTPS 隧道

```bash
cloudflared tunnel --url http://127.0.0.1:8080
# 输出里有一行 https://xxxx.trycloudflare.com —— 这就是 <你的隧道域名>
```

拿到后：把第 1 步的 `TGG_WEBAPP_URL` 填成它，重启服务。
**可达性自检**（三个都应 200 / 返回 JSON）：
```bash
curl -s https://<隧道域名>/miniapp/index.html | head -3
curl -s https://<隧道域名>/api/escrow/tonconnect-manifest.json
curl -s -o /dev/null -w "%{http_code}\n" https://<隧道域名>/icon-180.png
```

## 3. 让 Bot 认识这个 Mini App

在 **@BotFather**：`/mybots` → 选你的 bot → **Bot Settings → Configure Mini App → Enable Mini App** → 填 `https://<隧道域名>/miniapp`。
（或者不配也行：Bot 的**用法/帮助**回执里会附「📝 打开表单」按钮——给 bot 发 `/start` 或 `/help` 就能看到，按钮指向同一个 URL。）

## 4. 测试全流程（六个动作）

> 双方各一步、交替进行。**每次操作后等 10–15 秒再进下一步**（消息上链要一个出块时间）。
> 页面提示语「已提交到钱包签名。链上确认后订单状态将更新」= 已广播，不等于已确认——**用下面的链上命令核对**。

**① 买方**：Bot 里打开「📝 打开表单」→ 填金额/币种 → **生成邀请链接** → 复制。

**② 卖方**：打开邀请链接 → **确认接受**（订单建立，Bot 通知）。

**③ 双方各连一次钱包**（本步只做绑定，不花钱）：
- **卖方**：接受后页面直接出现「💳 连接钱包」→ 连**卖方钱包 B**（Tonkeeper 弹窗确认）→ 状态行显示 `已连接：…`、操作按钮出现。
- **买方**：**再打开一次自己的邀请链接**（就在 ① 复制的那个）→ 页面识别"已接受"→ 直接进资金区 → 连**买方钱包 A**。

**④ 管理员部署合约**（买方/卖方地址已从第③步入库，可以省传）：
```bash
curl -u admin:<密码> -X POST http://127.0.0.1:8080/admin/chain/deploy \
  -H 'Content-Type: application/json' \
  -d '{"orderId":<订单号>,"federationAddress":"kQCGdtFAuPCpAVsVdguEHRnShZnyslchRMYDLuWP1BU5RqhV",
       "asset":0,"chainAmountNano":"1000000000","deployValueNanoton":300000000}'
```
回执 `ok:true` + `contractAddress` 记下来。
**验证**：`~/.acton/bin/acton rpc info <contractAddress>` 应显示 `active`、余额 ≈0.3。
（说明：`chainAmountNano` 是**链上托管额**——测试用 1 TON=`"1000000000"`；与订单显示币种无关，jetton 单才用币种。）

**⑤ 买方入金**：买方资金区点「**支付入金（买方）**」→ Tonkeeper 弹窗（金额 ≈ 1.05 GRAM：托管 1 + gas 余量）→ 确认。
**验证**：
```bash
~/.acton/bin/acton rpc call <contractAddress> escrowState    # 应为 1
~/.acton/bin/acton rpc info <contractAddress> | grep Balance # ≈1.3 GRAM
```

**⑥ 卖方交付 → 买方确认（出款）**：
- 卖方点「**确认交付（卖方）**」→ 验证 `escrowState` → **2**；
- 买方点「**确认收货（买方）**」→ 验证 `escrowState` → **4**（RELEASED）；
- **卖方钱包 B 应收到 1 TON**（Tonkeeper 里看到账；合约余额降 ~1.0）。

**可选加演**：任一方点「**发起争议**」（state→3），然后联邦出裁决。
注意：`/admin/verdict` 的请求体需要**多签签名集**（`{orderId, outcome, reason, issuedAt, signatures}`——
signatures 的构造口径见 `FederationPartySignatureVerifier` 及其测试；**只出 RELEASE 裁决的链上效果**已在 B12 单独核销过，想省事可跳过本加演。

---

## 5. 每一步"哪里可能卡"（对照表）

| 现象 | 根因 | 处置 |
|---|---|---|
| Bot 里没有「打开表单」按钮 | `TGG_WEBAPP_URL` 没配/为空（留空会退化成纯文本） | 配好隧道 URL 重启 |
| 页面打开白屏/一直转 | 隧道断了，或 `telegram.org` 的 SDK 在客户端侧加载失败 | 重开隧道、Telegram 里下拉刷新 |
| 连上钱包但点按钮报 403 | 角色不符（FUND/CONFIRM=买方，DELIVER=卖方） | 确认当前账号在该订单中的角色 |
| 报「订单尚未部署链上合约」 | 第④步还没做 | 先部署 |
| 报「订单未绑定链上金额」 | 部署时没传 `chainAmountNano` 且库里也没有 | 部署时显式传 |
| Tonkeeper 弹窗来了，签名后无反应 | 钱包在 mainnet 模式（`network -3` 不匹配会被拒） | 钱包切 Testnet；或查 Tonkeeper 交易历史 |
| `escrowState` 读数不前进 | 消息可能还没上链 | 等 15 秒再读；仍不动→看第④⑤步的回执与钱包历史 |
| 报 `400 买方地址缺失` | 部署时买方没绑地址且请求也没传 | 让买方先做第③步，或部署请求里显式传 `buyerAddress` |

---

## 6. 收尾

- 全部走完 → 把 `ONLINE-VERIFICATION.md` 的 **B13** 行 `☐` 改 `✅`（附：合约地址、`escrowState` 读数、卖方到账截图/哈希）。
- 合约与钱包均为 testnet 资产，**演练完可弃**；想再演一遍就重新部署一个新订单合约。

---

## 7. 合约治理核销：紧急暂停（B14）与对账回填（B15）

> 前提：第 ②③ 线已就绪（合约已部署、订单在链上）。管理端点走 Basic 凭据（`TGG_ADMIN_USERNAME` / `TGG_ADMIN_PASSWORD`）。

### 7.1 紧急暂停（B14）

```bash
ADDR=<订单的链上合约地址>   # 部署回执 / orders 表 chain_contract_address
curl -u "$TGG_ADMIN_USERNAME:$TGG_ADMIN_PASSWORD" -X POST \
  -H 'Content-Type: application/json' \
  -d "{\"contractAddress\":\"$ADDR\"}" \
  http://127.0.0.1:8080/admin/pause            # 预期 {"ok":true,"message":"已发送暂停消息"}
```

1. **暂停生效**：在 Mini App（第 4 节的工具）以**买方**点「支付入金」——钱包签名后链上应**拒绝**（合约退出码 `0x1010` Paused；钱包历史可见失败交易）。
2. **幂等**：再 POST 一次 `/admin/pause` → 仍 200（不报"已暂停"）。
3. **解除**：`POST /admin/pause/unpause` → 200 → 买方再点「支付入金」→ 这次成功（state→LOCKED）。
4. **权限对照**：非联邦钱包发 Pause → 链上拒绝 `0x100b`（NotFederation）。可用失败回执间接核销（无需改联邦 seed——那会动生产配置）。
5. **治理不拦**：暂停期间在 Mini App 记一条存证（`RecordEvidence`，B11 路径）→ 应成功（暂停≠死锁）。

### 7.2 读面与对账回填（B15）

```bash
curl -u "$TGG_ADMIN_USERNAME:$TGG_ADMIN_PASSWORD" -X POST \
  -H 'Content-Type: application/json' -d '{"orderId": <订单号>}' \
  http://127.0.0.1:8080/admin/reconcile       # 出参含 chainState / verdict / actions
```

1. **读面**：`chainState` 字段应与合约当前状态一致（对照第 ③ 线各步的链上读数）。
2. **链上领先 → 回填**：让买方**经钱包直接入金**（Mini App 支付入金）但**不执行任何 bot 命令**；然后 `POST /admin/reconcile` → 预期 `verdict=BACKFILLED`、`actions=["markLocked"]`；**再 POST 一次** → `CONSISTENT`（幂等零动作）。
3. **危险场景**（故意的，练完弃号）：① 买方钱包入金（链上 LOCKED）→ ② 对账回填（链下 LOCKED）→ ③ bot 命令流 `deliver` + `release`（链下 RELEASED，链上仍 LOCKED）→ ④ `POST /admin/reconcile` → 预期 `verdict=DANGER` 且 `actions=[]`（**只报告不动手**——需人工介入）。
4. **未部署订单**：对没有 `chain_contract_address` 的订单 reconcile → `NOT_DEPLOYED`。

---

## 8. Bot 命令面冒烟（A20–A23 增量）

> 旧批 A1–A19 在 ONLINE-VERIFICATION A 表逐项核销；本节只覆盖近批新增的四项。

| # | 操作 | 预期观察 |
|---|---|---|
| A20 | 输入 `/` 弹出命令列表 | 12 条（escrow/管理命令）——菜单由 `setMyCommands` 注册 |
| A21 | Mini App 打开一笔订单 | 动作按钮按**角色过滤**（买方见 FUND/CONFIRM、卖方见 DELIVER）；未部署单见"尚未部署"提示 |
| A22 | **回复**对方消息发 `/escrow create 100 USDT`（不带 ID） | 预览含对方信息；`confirm` 回执引导"回复同一条消息"；**被回复者是 bot 时不误当目标** |

每项通过 → 回写 `ONLINE-VERIFICATION.md` 对应行 `☐ → ✅`。
（**A23 已作废**——管理留痕（GM-20）随群管理整体移除，2026-10-03。）
