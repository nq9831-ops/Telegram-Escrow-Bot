# Fragment API 集成 与 Permit Signature 钓鱼防御（整理）

> 来源：用户提交的《集成 Fragment API + 新增 Permit Signature 钓鱼防御》说明。
> 本文是整理 + 独立核实 + 对本项目的影响分析；「整理观察」为整理者补充，非原文。
> ⚠️ 本篇含**一条与本项目核心原则直接冲突的红旗**，见 3.1。

## 一、Fragment API：用 TON 购买 Stars 和 Premium（原文要点）

存在一个专注 Fragment 的第三方 SDK（原文称 `fragment-api-dev`），支持用 **GRAM 或 USDT on TON**
购买 Telegram **Stars 和 Premium**，**无需 API Key**，自动处理状态轮询。

**对项目的意义（原文）**：若"平台费自动划转 / 增值服务"需要收 Stars，可经 Fragment API 实现——
用户直接用 TON 钱包支付，SDK 自动完成 Stars 购买与交付，不必自建 Fragment 认证逻辑；
比手动操作 Fragment 网站更安全、更自动化。

## 二、Permit Signature 钓鱼防御（原文要点）

新加坡警察部队 2026 年 6 月发布的加密货币诈骗警示披露了一种新攻击手法：诱导用户批准
**"permit signature"（授权签名）**——一种**链下签名**，允许用户预先授权未来的交易。
因不立即转移资金，用户往往不觉察；攻击者随后可**无需进一步批准**就转走资产。

**对项目的意义（原文）**："防钓鱼提示"模块需增加：任何要求签名的请求都必须仔细核对签名内容；
若签名页面显示「授权使用代币」或「无限额度」，立即终止。应与 TON Connect 的签名确认流程配合，
**在用户签名前展示清晰的交易摘要**。

## 三、整理观察（补充，非原文）

### 3.1 ⚠️ 红旗：这类"无需 API Key"的 SDK 要求交出 TON 钱包**助记词**
核实（`pypi.org/project/fragment-api-py` 的依赖说明）显示，这类 Fragment SDK 的典型前置条件是：

> For KYC mode: Fragment cookies (stel_ssid, stel_dt, stel_token, stel_ton_token …) **+ TON wallet seed phrase (12/18/24 words)**
> + Tonconsole or Toncenter API key；For No-KYC mode: MarketApp API token **+ TON wallet seed phrase (12/18/24 words)** …

**即：它要你把钱包的 24 词助记词交给它**，才能代你发交易。这与本项目 V2.0 核心原则
「**服务器不碰私钥**」「资金安全变态级」**直接冲突**——交出助记词 = 交出钱包的完全控制权。
**建议：不采用此类 SDK 用于本项目**（尤其不要为"收平台费/卖 Stars"这类次要功能，去触碰用户或运营钱包的助记词）。
若确需 Stars 能力，优先用 **Telegram 官方 Bot API 的 Stars 支付**（生态内原生，不涉助记词）。

### 3.2 非官方 API 的 ToS 与失效风险
Fragment（fragment.com）**没有官方公开 API**；上述 `fragment-api-dev`、`fragment-stars-api`、
`fragmentapi.com`、`apifragment.online` 均为**第三方**。依赖它们意味着：
可能违反 Fragment 服务条款（自动化访问/cookie 复用），且**随时可能失效**——
把它放进资金相关路径是把可用性押在非官方接口上。

### 3.3 Permit Signature 防御：应并入既有防钓鱼能力（不新增编号）
项目已有 **T42（防钓鱼提示）、T55/T56（Comment 钓鱼）、T58 / G35（定期推送）**。
本条是在既有提示清单里**追加一类攻击**，不必新开功能项，直接扩充 T42/T55 的文案集合即可。
建议文案要点：链下授权签名 ≠ 无害；看到「授权/无限额度/approve」一律停止；签名前核对摘要。

### 3.4 反向要求：本项目自身不得制造"无限授权"面
Permit 钓鱼之所以能成，是因为**存在"预先授权、后续免批准"的原语**。本项目在设计合约与
TON Connect 交互时应**主动不制造这类面**：
- 合约**不提供**无限额度/长期授权接口（每笔交易的资金动作应绑定该笔订单）；
- 若未来引入任何 approve 类操作，必须让用户看到**额度上限与用途**，默认最小额度、默认不启用。
这是从"防钓鱼"推到"不生产钓鱼面"，比事后提示更根本。

### 3.5 签名前展示清晰摘要——与 TON Connect 材料呼应
本条的"签名前展示摘要"与 `docs/requirements/02-TON-Connect.md` 的托管钱包讨论同源：
无论托管与否，**签名确认界面展示人类可读的交易摘要**是本项目用户侧安全的必要项，
应作为 T8/T9/T10/T11（OAuth/支付/托管/资金动作）的统一交互要求。

### 3.6 待核实项
- 原文"**GRAM**"：TON 的原生币是 **Toncoin（TON）**；"GRAM" 是历史名/或某代币。
  落代码前须确认实际支付资产名（可能是笔误）。
- 这批第三方 SDK 的**实际可用性/费率**（`fragment-stars-api` 自称 "KYC 0% forever / No-KYC 0.25%"）——
  未经独立验证，仅作参考。

## 四、独立核实（本次整理所做）

| 断言 | 核实方式 | 结果 |
|---|---|---|
| 存在专注 Fragment 的 Python SDK | web_search（`github.com/slightbasebo/fragment-api-dev`、`bbbuilt/fragment-stars-api`、PyPI 多个包） | ✅ 存在**多个第三方**实现，非官方 |
| "无需 API Key" | web_search（`fragment-stars-api` PyPI 页） | ✅ 属实（但这些 SDK 仍需**钱包助记词**与 cookies） |
| 这类 SDK 需 TON 钱包助记词 | web_search（`fragment-api-py` 依赖说明） | ✅ 属实，原文列出 "TON wallet seed phrase (12/18/24 words)" |
| Permit signature 是 SPF 警示的攻击手法 | web_search（`police.gov.sg` 2026-07-01 警示；DAA LinkedIn 帖） | ✅ 属实，DAA 帖原文含 "Permit signature exploitation … approving off-chain" |
| 项目内已有防钓鱼能力 | grep（`docs/` 内 T42/T55/T56/T58/G35/README） | ✅ 已有多处，但**无 permit signature 条目** |
| 项目内有 Fragment 实现 | grep 全仓 | ❌ 无 |

## 五、给决策的收敛（建议）

1. **不采用** 3.1 描述的第三方 Fragment SDK（助记词冲突）——若要 Stars，走官方 Bot API 的 Stars 支付。
2. **可立即采纳** Permit Signature 作为 T42/T55 防钓鱼文案的一类（纯文案，无代码风险）。
3. **需架构决策**：把"不制造无限授权面"（3.4）与"签名前展示摘要"（3.5）写成 S5/T8–T11 的**设计约束**，
   在合约与前端设计时落实。
